# DYNAMIC RATE LIMITING THEO ĐỐI TÁC — MÔ TẢ CHI TIẾT CODE

> Thay vì chặn TPS cố định cho từng đầu API (mọi đối tác như nhau — không hợp lý), hệ thống cho
> phép **mỗi đối tác có hạn mức TPS riêng**, **nạp động từ DB** (đổi không cần restart), có thể
> **override theo từng API** và theo **gói dịch vụ (tier)**.
>
> Vùng code: `api-gateway/.../ratelimit/` + `config/RateLimiterConfig.java` + `application.yml` +
> bảng DB trong `db/schema.sql`.

---

## 1. Ý TƯỞNG & LUỒNG TỔNG QUAN

```
Đối tác --(X-Client-Id + X-Api-Key)--> [api-gateway]
   ApiKeyAuthFilter  → so khớp SHA-256(API_KEY) với api_key_hash → X-Client-Id tin cậy
        │
   RequestRateLimiter filter (mỗi route):
     ① PartnerKeyResolver   → khóa = clientId (đối tác)
     ② PartnerRateLimiter.isAllowed(routeId, clientId)
            │  tra hạn mức (in-memory, không chạm DB)
            ▼
        PartnerRateLimitConfigService.resolve(clientId, routeId)
            │  (snapshot làm tươi định kỳ từ DB)
            ▼
        token bucket Lua (ATOMIC trong Redis) với rate/burst RIÊNG của đối tác
            → allowed? → 200 (kèm header X-RateLimit-*)  |  vượt → 429
```

Hai điểm cốt lõi:
- **Đường request KHÔNG chạm DB** — chỉ tra `Map` in-memory; DB chỉ được đọc trong tác vụ
  `@Scheduled` nền (mỗi 15s) → không blocking event-loop của gateway (reactive).
- **Động**: đổi hạn mức trong DB → tự áp dụng sau ≤ 15s, **không cần restart**.

---

## 2. THIẾT KẾ DATABASE (3 bảng)

| Bảng | Vai trò |
|---|---|
| `rate_limit_tier` | Hạn mức **mặc định theo gói** (BRONZE/SILVER/GOLD/INTERNAL) |
| `partner` | Đối tác (`code` = `client_id`) + `status` + `tier_code` + `api_key_hash` (SHA-256 để xác thực) |
| `partner_rate_limit` | **Override** theo từng đối tác, có thể theo từng **api_scope** (routeId) + thời hạn + audit |

**Thứ tự ưu tiên khi tra hạn mức (cụ thể thắng):**
1. `partner_rate_limit` với `api_scope = routeId` (override theo đúng API).
2. `partner_rate_limit` với `api_scope = 'DEFAULT'` (override chung cho đối tác).
3. Hạn mức của **tier** mà đối tác thuộc về.
4. **Mặc định hệ thống** (`gateway.rate-limit.*` trong YAML) — fallback cuối.
- Đối tác `status = SUSPENDED` → hạn mức **0** (chặn hoàn toàn).

Schema (rút gọn — đầy đủ trong `product-catalog-service/src/main/resources/db/schema.sql`):
```sql
rate_limit_tier(tier_code PK, replenish_rate, burst_capacity, requested_tokens, ...)
partner(id PK, code UNIQUE, name, status, tier_code FK→tier, ...)
partner_rate_limit(id PK, partner_code, api_scope, replenish_rate, burst_capacity,
                   requested_tokens, enabled, valid_from, valid_to, updated_by,
                   UNIQUE(partner_code, api_scope))
```
Seed mẫu: tier BRONZE(20/40) SILVER(100/200) GOLD(500/1000) INTERNAL(2000/4000);
`PARTNER_A`(SILVER) có override `product-catalog`=300/600 và `product-management`=10/20;
`PARTNER_X` = SUSPENDED.

> **Vì sao thiết kế này tối ưu/đầy đủ:** tách *tier* (mặc định theo gói, đổi 1 chỗ áp cho nhiều
> đối tác) khỏi *override* (cá biệt theo đối tác/từng API); có `enabled` + `valid_from/to` (đặt lịch
> khuyến mãi/hạ tải tạm thời); có `updated_by/updated_at` (audit); `UNIQUE(partner_code, api_scope)`
> đảm bảo không trùng cấu hình; `CHECK(burst ≥ rate)` chống cấu hình sai.

---

## 3. GIẢI THÍCH TỪNG FILE CODE

### 3.1. `ratelimit/PartnerKeyResolver.java` — BƯỚC 1: nhận diện đối tác
```java
@Component("partnerKeyResolver")
public class PartnerKeyResolver implements KeyResolver {
    public Mono<String> resolve(ServerWebExchange exchange) {
        String clientId = exchange.getRequest().getHeaders().getFirst(HEADER_CLIENT_ID);
        if (clientId != null && !clientId.isBlank()) return Mono.just(clientId);
        // chưa xác thực → rơi về IP để vẫn chặn
        var remote = exchange.getRequest().getRemoteAddress();
        return Mono.just("ip:" + (remote != null ? remote.getAddress().getHostAddress() : "anonymous"));
    }
}
```
- Khóa rate-limit = **clientId** lấy từ header `X-Client-Id` mà `ApiKeyAuthFilter` đã **xác thực bằng
  API key** (đối tác phải có `X-Api-Key` khớp `api_key_hash` của chính client_id đó) → **không giả mạo**
  được để mượn hạn mức bên khác.
- Mỗi clientId → một "xô token" riêng. Request chưa xác thực → giới hạn theo IP.
- Bean được tham chiếu trong YAML: `key-resolver: "#{@partnerKeyResolver}"`.

### 3.2. `ratelimit/PartnerRateLimitConfigService.java` — nguồn cấu hình động
- Giữ một **snapshot bất biến** `volatile Map<String, PartnerConfig>`; đổi nguyên cụm mỗi lần refresh
  → đọc **lock-free**, an toàn trên event-loop.
- `@PostConstruct init()` nạp lần đầu; `@Scheduled(fixedDelayString="${gateway.rate-limit.refresh-ms:15000}")`
  refresh định kỳ: đọc `rate_limit_tier`, `partner_rate_limit` (chỉ bản `enabled` còn hiệu lực),
  `partner` rồi **dựng lại** map.
- `resolve(partnerCode, routeId)` thuần in-memory theo đúng thứ tự ưu tiên ở mục 2.
- **Fail-safe:** nếu DB lỗi khi refresh → **giữ snapshot cũ** + log cảnh báo, không làm sập gateway.
```java
public Limit resolve(String partnerCode, String routeId) {
    PartnerConfig pc = snapshot.get(partnerCode);
    if (pc == null) return systemDefault();                 // đối tác lạ
    if ("SUSPENDED".equalsIgnoreCase(pc.status())) return BLOCKED;  // rate 0
    Limit byApi = pc.overrides().get(routeId);   if (byApi != null) return byApi;
    Limit byDef = pc.overrides().get("DEFAULT"); if (byDef != null) return byDef;
    return pc.tierLimit() != null ? pc.tierLimit() : systemDefault();
}
```
> DB chỉ đọc trong `@Scheduled` (chạy trên scheduler thread) nên **JDBC blocking không chạm
> event-loop**. Hot path = tra Map → cực nhanh, không blocking.

### 3.3. `ratelimit/PartnerRateLimiter.java` — BƯỚC 2: rate limiter động
```java
@Component("partnerRateLimiter")
public class PartnerRateLimiter implements RateLimiter<PartnerRateLimiter.Config> {
  public Mono<Response> isAllowed(String routeId, String id) {
    Limit limit = configService.resolve(id, routeId);
    if (limit.replenishRate() <= 0)                         // SUSPENDED → chặn ngay
        return Mono.just(new Response(false, headers(limit, 0)));
    List<String> keys = keys(id, routeId);                  // hash-tag {clientId:route} → cùng slot
    List<String> args = [rate, burst, now, requested];
    return redis.execute(script, keys, args).next()
        .map(r -> new Response(toLong(r.get(0))==1, headers(limit, toLong(r.get(1)))))
        .onErrorResume(e -> Mono.just(new Response(true, ...)));   // Redis lỗi → FAIL-OPEN
  }
}
```
- Tra hạn mức của đối tác trên route → chạy **token bucket** trong Redis bằng **Lua script ATOMIC**
  với **tham số riêng** từng đối tác (khác `RedisRateLimiter` mặc định: tham số tĩnh theo route).
- Key có **hash-tag** `{…}` để 2 key (tokens/timestamp) cùng slot trên **Redis Cluster**.
- **Fail-open**: Redis sự cố → cho qua, tránh biến rate-limit thành điểm chết.
- Trả về header thông tin: `X-RateLimit-Remaining/Replenish-Rate/Burst-Capacity/Policy-Source`.

### 3.4. `config/RateLimiterConfig.java` — hạ tầng (RedisScript + scheduling)
- `@EnableScheduling` (cho refresh) + `@EnableConfigurationProperties(RateLimitProperties)`.
- Bean `partnerRateLimiterScript`: **Lua token bucket** (cùng thuật toán Spring Cloud Gateway):
  nạp token theo thời gian trôi qua × rate, chặn trên `capacity`, trừ `requested`, trả
  `{allowed, tokens_left}`, tự đặt TTL key. `rate=0` được chặn ở tầng Java trước khi gọi (tránh chia 0).

### 3.5. `ratelimit/RateLimitProperties.java` — giới hạn mặc định + chu kỳ refresh
`gateway.rate-limit.default-replenish-rate / default-burst-capacity / default-requested-tokens / refresh-ms`.

---

## 4. CẤU HÌNH YAML (đã đổi)

Route dùng limiter động (bỏ tham số tĩnh):
```yaml
- name: RequestRateLimiter
  args:
    key-resolver: "#{@partnerKeyResolver}"
    rate-limiter: "#{@partnerRateLimiter}"
```
DataSource **chỉ cho refresh**, fail-safe khởi động:
```yaml
spring.datasource:
  url: jdbc:mariadb://${DB_HOST}:${DB_PORT}/${DB_NAME}
  username: ${DB_USER}; password: ${DB_PASSWORD}
  hikari: { maximum-pool-size: 5, read-only: true, initialization-fail-timeout: -1 }
gateway.rate-limit:
  default-replenish-rate: 50
  default-burst-capacity: 100
  refresh-ms: 15000
```
`initialization-fail-timeout: -1` → DB chết lúc khởi động **không** làm sập gateway (fail-safe).

---

## 5. CÁCH QUẢN TRỊ (vận hành)

- **Đổi TPS 1 đối tác**: `UPDATE partner_rate_limit SET replenish_rate=..., burst_capacity=... WHERE partner_code='PARTNER_A' AND api_scope='product-catalog';` → áp dụng sau ≤ 15s, không restart.
- **Nâng/hạ gói**: `UPDATE partner SET tier_code='GOLD' WHERE code='PARTNER_A';`
- **Treo đối tác**: `UPDATE partner SET status='SUSPENDED' WHERE code='PARTNER_X';` → mọi request của họ bị chặn (429).
- **Đặt lịch khuyến mãi/hạ tải**: dùng `valid_from`/`valid_to` + `enabled`.

---

## 6. CÁCH TEST

```bash
# Xác thực bằng API key: header X-Client-Id + X-Api-Key (key demo trong schema.sql)
# Bắn vượt rate trên route catalog của PARTNER_A (override 300/600) → cuối cùng nhận HTTP 429
for i in $(seq 1 800); do
  curl -s -o /dev/null -w "%{http_code} " \
    -H "X-Client-Id: PARTNER_A" -H "X-Api-Key: bccs_ak_partnerA_demo" \
    http://localhost:8080/api/v1/products/<id>
done; echo
# Kỳ vọng: phần lớn 200, khi cạn xô token → 429. Header X-RateLimit-Remaining giảm dần.
# Đổi DB rồi chờ 15s, bắn lại → hạn mức mới có hiệu lực (không restart).
```
Kiểm chứng **độc lập theo đối tác**: bắn đồng thời PARTNER_A (key A) và PARTNER_B (key B, hạn mức thấp)
→ B bị 429 sớm hơn A dù cùng gọi 1 API. Sai/thiếu API key → 401 (chưa tính rate-limit).

---

## 7. LƯU Ý PRODUCTION

- **Quản trị qua Admin API** thay vì SQL tay (CRUD `partner`/`partner_rate_limit` ở management-service).
- **Refresh tức thời**: ngoài chu kỳ 15s, có thể bắn sự kiện (Kafka/Redis pub-sub) để gateway refresh ngay khi đổi hạn mức.
- **Tránh JDBC trong gateway reactive**: phương án "sạch" hơn là đẩy cấu hình vào **Redis** (sync từ DB) rồi gateway đọc reactive; hoặc dùng **R2DBC**. Bản hiện tại dùng JDBC nhưng **chỉ trong @Scheduled** nên chấp nhận được.
- **Redis Cluster**: key đã có **hash-tag** để token bucket atomic trong 1 slot.
- **Đồng bộ đa replica gateway**: token bucket nằm ở **Redis dùng chung** nên mọi replica gateway thấy cùng 1 xô → giới hạn đúng dù scale ngang.
- **429 thân thiện**: cân nhắc thêm header `Retry-After` và body lỗi chuẩn cho đối tác.
