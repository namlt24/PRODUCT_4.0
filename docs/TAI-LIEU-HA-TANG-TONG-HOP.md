# BCCS Product Catalog 4.0 — Tài liệu hạ tầng tổng hợp

> Tài liệu bảo vệ trước hội đồng. Gồm 5 phần:
> 1. Hạ tầng đã xây dựng thế nào, tại sao, đã best-practice chưa
> 2. Giải thích chi tiết code từng thành phần hạ tầng
> 3. Kế hoạch triển khai K8s môi trường test (kèm CI/CD)
> 4. Kế hoạch sizing + test tải (hướng dẫn từng bước)
> 5. Kế hoạch deploy production

---
---

# PHẦN 1 — Hạ tầng đã xây dựng như thế nào? Tại sao? Đã best-practice chưa?

## 1.1. Bức tranh tổng thể

```
                 ┌──────────────────────────────────────────────────────┐
   Đối tác       │                   API GATEWAY (8080)                  │
  (3rd-party) ──▶│  Trace → Auth(API key) → Rate-Limit động → CircuitBreaker│
                 └───────────┬───────────────────────┬──────────────────┘
                             │ đọc                    │ ghi
                   ┌─────────▼─────────┐    ┌─────────▼──────────────┐
                   │ product-catalog   │    │ product-management     │
                   │ (8081, READ)      │    │ (8082, WRITE)          │
                   │ L1 Caffeine       │    │ @Transactional         │
                   │ L2 Redis          │    │ afterCommit → Kafka    │
                   └───┬───────┬───────┘    └─────────┬──────────────┘
                       │       │   ▲                  │ catalog.changes
                  Redis│    DB │   └── evict ─ Kafka ◀┘  (sau khi commit)
                       ▼       ▼
                   [Redis]  [MariaDB bccs_catalog]  ◀── integration-service (8083, auth NỘI BỘ FE↔BE)
```

**4 service + 1 thư viện dùng chung:**
- `api-gateway` — WebFlux/reactive, điểm vào duy nhất; xác thực, định tuyến, rate-limit, circuit breaker.
- `product-catalog-service` — servlet + virtual threads; đường ĐỌC sub-ms qua cache 2 tầng.
- `product-management-service` — servlet; đường GHI, phát sự kiện đổi dữ liệu.
- `integration-service` — xác thực người dùng **nội bộ** (FE quản trị ↔ BE); không phục vụ đối tác.
- `common-lib` — trace context (W3C), hằng số MDC, DTO lỗi dùng chung.

## 1.2. Vì sao chia như vậy (lý do kiến trúc)

| Quyết định | Lý do | Đánh đổi |
|---|---|---|
| **Tách đọc/ghi (CQRS-light)** | Đường đọc chiếm ~95% tải của catalog 3rd-party; tách giúp scale đọc độc lập, tinh chỉnh cache/pool riêng | Hai entity cùng map 1 bảng — phải giữ đồng bộ |
| **Gateway reactive, service servlet** | Gateway I/O-bound (fan-out, chờ downstream) hợp reactive; service nghiệp vụ viết kiểu blocking dễ bảo trì, đã có virtual threads gánh đồng thời | Hai mô hình lập trình khác nhau |
| **Cache 2 tầng (Caffeine + Redis)** | L1 in-process = 0 network, ~ns; L2 Redis chia sẻ giữa các pod, sống sót khi pod restart | Phải tự xử lý nhất quán giữa 2 tầng |
| **Vô hiệu hóa cache theo SỰ KIỆN (Kafka)** | TTL thuần thì dữ liệu cũ tồn tại tới hết TTL; event evict đúng key ngay sau ghi | Phụ thuộc Kafka (đã fail-open) |
| **Phát event SAU khi commit** | Không bao giờ evict cache cho 1 giao dịch bị rollback | Có cửa sổ rất ngắn cache cũ giữa commit và event |
| **Xác thực đối tác bằng API key tại gateway** | Sub-ms, tra hash trong snapshot in-memory, không gọi mạng; thu hồi tức thì bằng xóa/đổi key trong DB | Stateful (cần nạp/cache); auth fail-closed khi DB config sập |
| **Rate-limit động theo đối tác** | Mỗi đối tác khai TPS riêng; chặn tĩnh theo API là bất hợp lý | Gateway phụ thuộc DB để nạp cấu hình (đã fail-safe) |

## 1.3. Ba trụ cột "chịu lỗi" (resilience) — điểm cốt lõi để bảo vệ

1. **Fail-open ở tầng cache:** mọi thao tác Redis bọc try/catch, lỗi → rơi xuống DB. Redis chết → vẫn phục vụ, chỉ chậm hơn. Readiness probe chỉ phụ thuộc `db,ping` nên Redis/Kafka degrade KHÔNG làm pod un-ready.
2. **Fail-open ở rate-limit:** Redis lỗi → cho request qua thay vì biến rate-limit thành điểm chết toàn cục.
3. **Fail-safe ở nạp cấu hình:** DB lỗi lúc refresh → giữ snapshot cũ; DB lỗi lúc khởi động (`initialization-fail-timeout:-1`) → gateway vẫn lên, dùng giới hạn mặc định.

→ **Không một node hạ tầng đơn lẻ nào (Redis/Kafka/DB-config) làm sập nền tảng.**

## 1.4. Chống 3 chế độ hỏng kinh điển của cache

- **Avalanche (đổ tuyết):** mỗi TTL Redis = base + jitter ngẫu nhiên → hàng triệu key không hết hạn cùng lúc.
- **Penetration (xuyên cache):** id không tồn tại được cache bằng `__NULL__` (TTL ngắn) → id rác không đấm vào DB.
- **Breakdown/outage:** fail-open xuống DB như trên.

## 1.5. Đã best-practice chưa? (đánh giá thẳng thắn)

**✅ Đã đạt:**
- 12-factor config (toàn bộ qua env/ConfigMap/Secret), không hardcode secret.
- Stateless service → scale ngang được, có HPA.
- Health probe tách readiness/liveness, readiness không phụ thuộc dependency không thiết yếu.
- Distributed tracing W3C chuẩn, log JSON có traceId xuyên Kafka.
- Circuit breaker + retry + timeout ở ranh giới mạng.
- Pool DB sizing có chủ đích (read 40 + write 20 < max_connections).
- Rate-limit động, hash-tag key sẵn sàng Redis Cluster, idempotent consumer.

**⚠️ Chưa đạt / còn nợ (cho production):**
- **Tầng data single-node** (MariaDB/Redis/Kafka 1 node) — chưa HA. *Đây là việc hạ tầng ngoài, không phải sửa code.*
- **Schema bằng `schema.sql` mount-init**, chưa có Flyway/Liquibase → dễ lệch môi trường.
- **Secrets là `.env` demo** — production cần Vault/Sealed-Secrets + xoay creds.
- **Chưa có CI/CD, chưa load-test** để có số sizing thật.
- **Spring CacheManager `l1Products/l1Offers` đang không dùng** → `cache_gets_total` của Micrometer = 0 (hit/miss thật nằm ở Caffeine tự quản, chưa bind MeterRegistry).
- ES observability single-node, chưa retention/ILM, chưa alerting.

**Kết luận mục 1:** Kiến trúc *ứng dụng* đã theo best-practice (resilient, observable, scalable, 12-factor). Khoảng trống còn lại nằm ở *hạ tầng vận hành* (HA, secrets, CI/CD, sizing) — phần lớn là cấu hình/hạ tầng ngoài, xử lý ở giai đoạn production mà không phải viết lại code (xem `LO-TRINH-TRIEN-KHAI-THEO-GIAI-DOAN.md`).

---
---

# PHẦN 2 — Giải thích chi tiết code từng thành phần hạ tầng

> Chỉ tập trung các thành phần *làm nên tính hạ tầng* của nền tảng. Phần entity/DTO/CRUD
> thuần xem `GIAI-THICH-CHI-TIET-CODE-JAVA.md`.

## 2.1. Trace Context tại Gateway — `TraceContextGlobalFilter`

Filter chạy **đầu tiên** (`HIGHEST_PRECEDENCE`) để mọi log sau đó có traceId.

```java
String traceparent = request.getHeaders().getFirst(MdcConstants.W3C_TRACEPARENT);
String traceId = TraceContext.extractTraceId(traceparent);   // lấy từ traceparent đối tác gửi
if (traceId == null) traceId = TraceContext.newTraceId();     // không có → tạo UUID-v4
spanId = TraceContext.newSpanId();                            // gateway luôn mở span MỚI cho hop downstream
```
- Nếu đối tác gửi `traceparent` (W3C) → **nối tiếp** trace của họ; nếu không → sinh trace mới.
- `request.mutate().header(...)` chèn `X-Trace-Id/X-Span-Id/traceparent` xuống downstream để service servlet hydrate lại MDC.
- `exchange.getAttributes().put(MDC_TRACE_ID, traceId)` lưu lại để `ApiKeyAuthFilter` trả vào body lỗi 401.
- `MDC.put(...)` để log JSON của chính gateway mang traceId; `.doFinally(... MDC.clear())` **bắt buộc** vì luồng reactive dùng lại thread — không clear sẽ rò trace sang request khác.

## 2.2. Xác thực ĐỐI TÁC tại Gateway — `ApiKeyAuthFilter`

> Đối tác xác thực bằng cặp **(X-Client-Id, X-Api-Key)** ngay tại gateway — **KHÔNG gọi
> integration-service**. (FE/người dùng nội bộ dùng đường auth riêng, xem mục 2.2b.)

`getOrder() = HIGHEST_PRECEDENCE + 10` → chạy ngay sau trace filter, trước định tuyến.

```java
if (isPermitAll(path)) return chain.filter(exchange);                    // health/metrics/fallback bỏ qua
String clientId = ...getFirst("X-Client-Id");
String apiKey   = ...getFirst("X-Api-Key");
if (clientId/apiKey rỗng) return unauthorized(...);                      // thiếu → 401
if (!partnerRegistry.authenticate(clientId, apiKey)) return unauthorized(...); // sai/treo → 401
// hợp lệ: chuẩn hóa X-Client-Id (tin cậy) + XÓA X-Api-Key khỏi request downstream
request.mutate().header("X-Client-Id", clientId).headers(h -> h.remove("X-Api-Key"))...
```

**`authenticate(clientId, rawApiKey)`** trong `PartnerRateLimitConfigService`:
- Tra `partner` theo `clientId` trong **snapshot in-memory** (cùng nguồn nạp với rate-limit, không chạm DB trên hot path).
- Tính **SHA-256(rawApiKey)** rồi so khớp với `api_key_hash` đã lưu, **so sánh hằng thời gian** (`MessageDigest.isEqual`) → chống timing attack.
- Đối tác lạ / chưa cấu hình key / `SUSPENDED` → `false` → 401.

**Vì sao an toàn:** API key chứng minh quyền sở hữu `client_id`, nên sau bước này `X-Client-Id` là **tin cậy** → mục 2.4 dùng nó làm khóa rate-limit không sợ giả mạo. `api_key_hash` (không phải key thô) lưu trong DB → DB leak cũng không lộ key.

**Fail-closed (đúng bản chất auth):** nếu DB config sập lúc khởi động → snapshot rỗng → đối tác bị 401. Auth **không thể** fail-open như rate-limit; vì vậy gateway `depends_on mariadb healthy`.

## 2.2b. Xác thực NỘI BỘ — `integration-service`

Đối tác đã tách khỏi đây. `integration-service` giờ verify **JWT của người dùng nội bộ** (FE quản trị ↔ BE) bằng RSA public key (từ IdP/SSO nội bộ); không còn introspection nhà cung cấp đối tác. Định hướng production: tích hợp IdP có sẵn (Keycloak/AD/LDAP) + RBAC theo vai trò cho đường ghi.

## 2.3. Nguồn cấu hình rate-limit — `PartnerRateLimitConfigService`

Đây là phần "load cấu hình động từ DB/Cache". **Tách hẳn việc đọc DB ra khỏi hot path.**

```java
private volatile Map<String, PartnerConfig> snapshot = Map.of();  // đọc lock-free
```
- `volatile` + thay nguyên cụm: request đọc snapshot không cần khóa; refresh dựng map mới rồi gán đè 1 lần.

```java
@Scheduled(fixedDelayString = "${gateway.rate-limit.refresh-ms:15000}")
public void refresh() { ... }       // 15s/lần nạp lại từ DB
@PostConstruct public void init() { refresh(); }  // nạp ngay khi khởi động
```
3 truy vấn dựng snapshot: (1) `rate_limit_tier`, (2) `partner_rate_limit` **chỉ bản đang hiệu lực** (`enabled=1` và trong `valid_from..valid_to`), (3) `partner` + tier. Lỗi DB → `catch` giữ snapshot cũ (**fail-safe**).

**`resolve(partnerCode, routeId)` — thứ tự ưu tiên (cụ thể thắng):**
```java
if (pc == null) return systemDefault();                  // đối tác lạ
if (SUSPENDED) return BLOCKED;                            // treo → rate 0
Limit byApi = overrides.get(routeId);  if (byApi!=null) return byApi;      // 1. override theo API
Limit byDefault = overrides.get("DEFAULT"); if(...) return byDefault;      // 2. override chung đối tác
if (tierLimit != null) return tierLimit;                                   // 3. theo gói (tier)
return systemDefault();                                                    // 4. mặc định hệ thống
```
Toàn bộ chỉ tra `Map` in-memory → **an toàn gọi trên event-loop của WebFlux**.

## 2.4. Bộ giới hạn — `PartnerRateLimiter` + Lua token bucket

`@Component("partnerRateLimiter") implements RateLimiter<Config>` để Spring Cloud Gateway gọi qua YAML `rate-limiter: "#{@partnerRateLimiter}"`.

```java
public Mono<Response> isAllowed(String routeId, String id) {   // id = clientId từ KeyResolver
    Limit limit = configService.resolve(id, routeId);          // hạn mức riêng đối tác (in-memory)
    if (limit.replenishRate() <= 0)                            // đối tác treo
        return Mono.just(new Response(false, headers(limit, 0)));
    ...
    return redis.execute(script, keys, args).next()            // token bucket ATOMIC trong Redis
        .map(result -> new Response(toLong(result.get(0))==1L, headers(...)))
        .onErrorResume(e -> Mono.just(new Response(true, ...)));// Redis lỗi → FAIL-OPEN cho qua
}
```

**Khóa Redis có hash-tag:**
```java
String prefix = "rate_limit.{" + partnerKey + ":" + routeId + "}";  // {…} ép cùng slot trên Cluster
return List.of(prefix + ".tokens", prefix + ".timestamp");
```
→ Mỗi (đối tác, route) một xô token độc lập. Vì xô nằm ở **Redis chung**, mọi replica gateway dùng chung 1 trạng thái → giới hạn đúng tổng dù scale nhiều pod.

**Lua token bucket (`RateLimiterConfig.TOKEN_BUCKET_LUA`)** chạy nguyên tử trong Redis:
```lua
filled_tokens = min(capacity, last_tokens + (now-last_refreshed)*rate)  -- nạp lại theo thời gian trôi
allowed = filled_tokens >= requested
if allowed then new_tokens = filled_tokens - requested end
setex(tokens_key, ttl, new_tokens); setex(timestamp_key, ttl, now)
return { allowed_num, new_tokens }
```
- `rate` = TPS ổn định (đổ token/giây); `capacity` = burst (đỉnh ngắn). Tham số **theo đối tác**, khác hẳn `RedisRateLimiter` mặc định (tham số tĩnh theo route trong YAML).
- Nguyên tử ⇒ nhiều pod gọi đồng thời không bị đếm sai (race).
- Trả header `X-RateLimit-Remaining / Replenish-Rate / Burst-Capacity / Policy-Source` để đối tác tự điều tiết và để debug biết hạn mức đến từ nguồn nào (tier/override/default).

## 2.5. Cache 2 tầng — `TwoLevelCacheService` (trái tim đường đọc)

```java
public <T> T get(String namespace, String key, Class<T> type, Supplier<T> loader) {
    String cacheKey = namespace + ":" + key;
    Object l1 = this.l1.getIfPresent(cacheKey);  if (l1!=null) return unwrap(l1,type);  // L1 Caffeine
    Object l2 = safeRedisGet(cacheKey);                                                  // L2 Redis (fail-open)
    if (l2!=null) { this.l1.put(cacheKey,l2); return unwrap(l2,type); }                  // back-fill L1
    T loaded = loader.get();                                                             // DB
    if (loaded==null) { l1.put(cacheKey,NULL_MARKER); safeRedisPut(cacheKey,NULL_MARKER,nullTtl); return null; }
    l1.put(cacheKey,loaded); safeRedisPut(cacheKey,loaded, randomizedTtl()); return loaded;
}
```
- **L1 Caffeine** tự quản (không dùng Spring CacheManager) để toàn quyền điều phối L1↔L2 và null-marker: `maximumSize(100k)`, `expireAfterWrite(30s)`, `recordStats()`.
- **`safeRedisGet/safeRedisPut`** bọc try/catch → Redis chết thì log + rơi xuống DB (**fail-open**).
- **`randomizedTtl()` = base + jitter** → chống avalanche. **`NULL_MARKER`** → chống penetration.
- **`unwrap`**: Redis trả JSON của record (final class) về dạng `Map` (vì `GenericJackson2JsonRedisSerializer` không nhúng type cho final class) → `objectMapper.convertValue(value, type)` ép kiểu lại. *Giữ fallback này nếu sửa serialization.*
- **`evict(namespace,key)`** xóa cả 2 tầng — gọi bởi consumer Kafka bên dưới.

## 2.6. Vô hiệu hóa cache hướng sự kiện

**Bên ghi — `ProductManagementService.publishAfterCommit`:**
```java
if (TransactionSynchronizationManager.isSynchronizationActive()) {
    TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
        public void afterCommit() { eventPublisher.publish(event); }   // CHỈ phát SAU khi commit
    });
} else { eventPublisher.publish(event); }
```
→ Nếu giao dịch rollback, event **không** được phát → catalog không bao giờ evict nhầm cho 1 thay đổi bị hủy.

**Bên đọc — `CatalogChangeConsumer.onCatalogChange`:**
```java
restoreTraceContext(record);                       // khôi phục traceId/clientId từ Kafka header → MDC
...
cache.evict(namespace, event.entityId());          // xóa đúng 1 key ở cả 2 tầng
ack.acknowledge();                                 // chỉ commit offset SAU khi xử lý xong (at-least-once)
```
- `restoreTraceContext` lấy traceId từ **Kafka record header** → dòng log "Evicted cache ..." truy ngược được về request ghi gốc trên Kibana.
- `ack.acknowledge()` sau xử lý → an toàn nếu pod chết giữa chừng (xử lý lại; evict idempotent nên lặp không hại).
- `MDC.clear()` trong `finally` chống rò trace giữa các message.

## 2.7. `common-lib` — vì sao tách riêng

`TraceContext` (parse/sinh `traceparent` W3C) và `MdcConstants` (tên khóa MDC / HTTP header / Kafka header **chuẩn hóa một chỗ**). Đổi tên khóa ở đây, không sửa rải rác từng service → tránh lệch header làm đứt correlation.

---
---

# PHẦN 3 — Kế hoạch triển khai K8s môi trường test (kèm CI/CD)

## 3.1. Cụm test bằng kind (Kubernetes-in-Docker)

Đã có sẵn manifest trong `k8s/test/`:
- `00-namespace-config.yaml` — namespace `bccs` + ConfigMap/Secret.
- `10-infra.yaml` — MariaDB, Redis, Kafka (KRaft, `KAFKA_CONTROLLER_QUORUM_VOTERS=1@localhost:9093` để né deadlock endpoint khi pod chưa Ready).
- `20-services.yaml` — 4 service + Service + (HPA per service trong `<svc>/k8s/hpa.yaml`).

**Quy trình dựng:**
```bash
kind create cluster --name bccs-test
kind load docker-image <image>:<tag> --name bccs-test   # nạp image build local vào cụm
kubectl apply -f k8s/test/00-namespace-config.yaml
kubectl apply -f k8s/test/10-infra.yaml
kubectl -n bccs rollout status deploy/mariadb deploy/redis deploy/kafka
kubectl apply -f k8s/test/20-services.yaml
kubectl -n bccs get pods         # chờ 7/7 Running
```
**Lưu ý kind ≠ k8s thật:** không có LoadBalancer thật, storageClass khác, networking khác → cụm test chỉ chứng minh *logic*, không chứng minh chịu tải/HA.

## 3.2. Giám sát trỏ vào cụm test
`k8s/monitoring/`: `10-prometheus-grafana.yaml`, `20-elk.yaml` (ES+Kibana+Filebeat), `30-apm.yaml` (`wait_for_integration:false`). Truy cập qua `kubectl -n monitoring port-forward` (Grafana 3000, Kibana 5601, Prometheus 9090) — mỗi lệnh giữ 1 terminal sống.

## 3.3. CI/CD — kế hoạch (chưa có, cần bổ sung)

**Pipeline mục tiêu (GitHub Actions ví dụ):**
```
push/PR ─▶ [CI]  build (container Java 21) ─▶ mvn test ─▶ build image ─▶ Trivy scan
                                                                   │
   main  ─▶ [CD]  push image (tag = git SHA) ─▶ apply/argo sync ─▶ rollout status ─▶ smoke test
```

**Nguyên tắc bắt buộc:**
- Build trong **container Java 21** (host JDK 17 không compile được — xem CLAUDE.md).
- **Tag image theo git SHA**, không `:latest` → rollback xác định.
- **Scan image** (Trivy) chặn merge nếu có CVE nghiêm trọng.
- CD nên **GitOps** (ArgoCD/Flux): Git là nguồn sự thật, mọi thay đổi cluster có audit + rollback.

**Khung `.github/workflows/ci.yaml` (mẫu rút gọn):**
```yaml
jobs:
  build-test:
    runs-on: ubuntu-latest
    container: maven:3.9-eclipse-temurin-21
    steps:
      - uses: actions/checkout@v4
      - run: mvn -B -pl common-lib,<service> -am test
  image:
    needs: build-test
    steps:
      - run: docker build -f <service>/Dockerfile -t $REGISTRY/<service>:${GITHUB_SHA} .
      - run: trivy image --exit-code 1 --severity CRITICAL $REGISTRY/<service>:${GITHUB_SHA}
      - run: docker push $REGISTRY/<service>:${GITHUB_SHA}
  deploy:                       # chỉ nhánh main
    steps:
      - run: kubectl set image deploy/<service> <service>=$REGISTRY/<service>:${GITHUB_SHA} -n bccs
      - run: kubectl rollout status deploy/<service> -n bccs --timeout=120s
```

**Khác biệt test vs prod trong CD:** test có thể `kubectl set image` trực tiếp; prod nên qua GitOps + duyệt (approval) + canary/blue-green.

---
---

# PHẦN 4 — Kế hoạch sizing & test tải (hướng dẫn từng bước)

> Mục tiêu: từ con số đo thực, suy ra cần bao nhiêu pod / CPU / RAM / pool để chịu tải mục tiêu
> (~hàng triệu request/ngày). **Không đoán — đo rồi mới cấu hình.**

## 4.1. Khái niệm nền (đọc trước khi đo)

- **Định luật Little:** `concurrency = throughput × latency`. Ví dụ muốn 2000 req/s ở p95 = 50ms → đồng thời ≈ 100 request đang xử lý. Đây là cơ sở để chọn pool/thread.
- **Quy đổi tải:** "1 triệu req/ngày" rất thấp nếu trải đều (~12 req/s), nhưng đỉnh giờ cao điểm có thể gấp 5–10 lần trung bình → **luôn sizing theo PEAK, không theo trung bình.**
- **Chỉ số phải đo:** throughput (req/s) tối đa trước khi p95 vọt; p50/p95/p99 latency; tỉ lệ lỗi; CPU/RAM mỗi pod ở mức tải đó; cache hit ratio; mức bão hòa DB pool.

## 4.2. Chuẩn bị môi trường đo
- Đo trên cụm **giống prod nhất có thể** (kind không đại diện về tài nguyên — chỉ dùng để dựng kịch bản).
- Tải dữ liệu thật/giả đủ lớn (vài chục nghìn product/offer) để cache không "ăn may" 100% hit.
- Bật sẵn observability (Prometheus/Grafana) để xem CPU, GC, pool, latency **trong lúc bắn**.
- **Tắt rate-limit hoặc nâng hạn mức INTERNAL** cho client test, nếu không bạn đang đo rate-limit chứ không đo năng lực service.

## 4.3. Công cụ — dùng k6 (gọn, kịch bản bằng JS)

Cài: `choco install k6` (Windows) hoặc `docker run grafana/k6`.

**Kịch bản tăng dần để tìm điểm gãy (`load-test.js`):**
```javascript
import http from 'k6/http';
import { check } from 'k6';

export const options = {
  stages: [
    { duration: '1m', target: 50 },    // khởi động
    { duration: '2m', target: 200 },   // tăng
    { duration: '2m', target: 500 },   // tăng tiếp
    { duration: '2m', target: 1000 },  // ép tải
    { duration: '1m', target: 0 },     // hạ
  ],
  thresholds: {
    http_req_duration: ['p(95)<200', 'p(99)<500'],  // SLO: p95<200ms, p99<500ms
    http_req_failed: ['rate<0.01'],                  // lỗi <1%
  },
};

export default function () {
  const res = http.get('http://<gateway-host>:8080/api/v1/products/PROD-001', {
    headers: { 'X-Client-Id': __ENV.CLIENT_ID, 'X-Api-Key': __ENV.API_KEY },
  });
  check(res, { 'status 200': (r) => r.status === 200 });
}
```
Chạy: `k6 run -e CLIENT_ID=PARTNER_GOLD -e API_KEY=bccs_ak_gold_demo load-test.js`
(dùng đối tác hạn mức cao như GOLD/INTERNAL để đo năng lực service, không bị rate-limit chặn sớm)

## 4.4. Quy trình đo (4 bước)

1. **Baseline 1 pod:** scale service về 1 replica, bắn k6 tăng dần. Ghi lại **throughput tối đa của 1 pod** tại điểm p95 bắt đầu vọt và **CPU/RAM** lúc đó. Đây là "đơn vị năng lực".
2. **Tìm nút thắt:** khi p95 vọt, nhìn Grafana xem ai bão hòa trước — CPU pod? DB pool cạn (active = max)? Redis? GC? → chỉnh đúng chỗ đó (tăng pool, tăng CPU limit, tăng cache TTL...).
3. **Kiểm chứng scale ngang:** tăng lên 3–5 pod, bắn lại. Throughput có tăng gần tuyến tính không? Nếu không → nút thắt đã dời xuống DB/Redis (tầng data cần HA/scale).
4. **Suy ra sizing:** `số pod = ceil(throughput_đỉnh_mục_tiêu / throughput_1_pod) × hệ_số_an_toàn(1.5–2)`. Đặt `requests` = mức dùng ổn định, `limits` = đỉnh; cấu hình HPA `targetCPU` ~ 60–70% để còn dư đầu khi scale.

## 4.5. Áp số đo vào cấu hình
- `resources.requests/limits` mỗi pod ← CPU/RAM đo ở bước 1.
- `HPA minReplicas/maxReplicas/targetCPU` ← bước 4.
- HikariCP pool ← bước 2 (đừng để tổng pool > `max_connections` MariaDB).
- Cache TTL/`maximumSize` ← cân theo hit ratio quan sát được.
- Ghi lại thành **bảng sizing** kèm điều kiện đo (ngày, dữ liệu, phần cứng) để bảo vệ trước hội đồng.

## 4.6. Các loại test nên chạy
| Loại | Mục đích |
|---|---|
| **Load** (tăng dần) | Tìm throughput tối đa & điểm p95 gãy |
| **Stress** (vượt ngưỡng) | Xem hệ thống xuống cấp có "duyên" không (429/503 sạch hay sập) |
| **Soak** (giữ tải vừa 1–2h) | Phát hiện rò bộ nhớ, GC dài, pool rò |
| **Spike** (nhảy đột ngột) | Kiểm chứng HPA kịp scale & circuit breaker |

---
---

# PHẦN 5 — Kế hoạch deploy production

## 5.1. Tiền đề (phải xong trước go-live)
- [ ] Đã có **CI/CD** (Phần 3) và **số sizing thật** (Phần 4).
- [ ] **Migration schema** chuyển sang Flyway/Liquibase (bỏ `schema.sql` mount-init).
- [ ] **Secrets** chuyển sang Vault/Sealed-Secrets; **xoay toàn bộ creds demo**.

## 5.2. Hạ tầng production (chủ yếu là hạ tầng ngoài, ít đụng code)

**Tầng data — HA (HA ≠ backup):**
- MariaDB: Galera multi-master hoặc primary-replica (hoặc managed DB). Đổi connection string, không đổi code.
- Redis: Sentinel hoặc Cluster (key rate-limit/cache đã có hash-tag sẵn sàng Cluster).
- Kafka: ≥3 broker, replication-factor=3, min-ISR=2.

**Tầng mạng/vào:**
- Ingress + TLS (cert-manager), LB (mềm/cứng — **chi tiết ở mục 5.6**), tùy chọn WAF trước gateway.
- Gateway ≥2 replica sau LB (đã stateless).

**Vận hành:**
- Alerting (Alertmanager): p99 latency, tỉ lệ 5xx, spike 429, pod restart, Kafka lag, độ trễ replica DB, đầy disk ES.
- ES cluster + retention/ILM cho log.
- Backup DB hằng ngày (`scripts/db-backup/`) + **test restore định kỳ** (backup chưa test = chưa có backup).

## 5.3. Cụm K8s production
- ≥3 worker, **multi-AZ**; `topologySpreadConstraints` để pod trải đều AZ.
- `PodDisruptionBudget` để nâng cấp node không hạ quá nhiều replica cùng lúc.
- `requests/limits` + HPA theo số sizing; ResourceQuota/LimitRange cho namespace.
- NetworkPolicy hạn chế lưu lượng đông-tây.

## 5.4. Chiến lược phát hành
- **Rolling update** mặc định (đã có readiness probe → không nhận traffic khi chưa sẵn sàng).
- Với thay đổi rủi ro cao: **canary** hoặc **blue-green** + smoke test tự động, rollback bằng tag SHA cũ.
- Migration DB **tương thích ngược** (expand/contract) để rolling update không vỡ schema.

## 5.5. Checklist go-live (tóm tắt)
- [ ] HA đủ 3 tầng data, đã thử kill-node thấy tự lành.
- [ ] Secrets thật + đã xoay creds demo.
- [ ] CI/CD chạy, rollback thử thành công.
- [ ] Sizing từ load-test, HPA cấu hình theo số đo.
- [ ] Alerting + dashboard + log retention bật.
- [ ] Backup + **restore đã test**.
- [ ] Runbook sự cố (Redis down, DB failover, Kafka lag, gateway 5xx) viết sẵn.

## 5.6. Lớp Load Balancer (nginx) trước cụm Gateway

Mô hình mục tiêu: **đối tác gọi vào MỘT điểm vào duy nhất (LB), LB chia tải cho cụm Gateway,
Gateway định tuyến xuống service.**

```
Đối tác ──DNS──▶ [ LB: nginx ] ◀── TLS kết thúc ở đây
                      │  L7, round-robin, health-check
                      ▼
            ┌──────────────────────────┐
            │  CỤM API GATEWAY (N pod)  │  ← stateless, ≥2 bản
            └────────────┬─────────────┘
                         │ auth API key + rate-limit chạy ở đây
                         ▼
            K8s Service (LB nội bộ) ──▶ catalog / management / integration (mỗi loại N pod)
```

### Vì sao cần LB — tại sao Gateway KHÔNG tự làm LB cho chính nó
Gateway có cân bằng tải, nhưng theo chiều **đi xuống service**. Việc chia request **từ đối tác VÀO
các gateway pod** phải xảy ra **trước khi** chạm bất kỳ gateway nào → không gateway nào tự làm được:
- **Con gà–quả trứng:** để HA cần N gateway pod; đối tác cần 1 địa chỉ; ai chọn pod-1 hay pod-2? Quyết định đó nằm **ngoài** mọi gateway.
- **IP pod đổi liên tục** (restart/reschedule) → cần điểm vào ổn định (VIP/DNS) đứng trước.
- **Failover:** LB qua health-check tự loại pod chết; gateway đang chết không tự báo client "đừng gọi tôi".
- **Phân lớp:** LB đơn giản → cực bền; gateway phức tạp (auth/rate-limit) → được phép scale/restart.

### Hai tầng cân bằng tải (đừng nhầm)
1. **nginx LB** → chia request cho các **pod gateway**.
2. **K8s Service** → gateway gọi xuống downstream, Service tự chia cho các **pod service**.

### ⚠️ LB phải tự HA — "1 con nginx" = SPOF mới
Một nginx duy nhất chết → sập toàn hệ dù gateway HA. Ba cách làm LB không-chết:

| Cách | Mô tả | Khi nào |
|---|---|---|
| **2 nginx + VIP (keepalived)** | active-passive, chung 1 IP ảo | tự dựng trên VM/bare-metal |
| **nginx sau LB cloud/phần cứng** | Cloud LB (hoặc F5) trước 2+ nginx | có hạ tầng cloud/DC |
| **ingress-nginx trong K8s** | chính là nginx, ≥2 replica, sau 1 Service LoadBalancer | **khuyến nghị khi đã ở K8s** |

> Trong K8s, "con nginx làm LB" thường **chính là ingress-nginx controller** — không cần dựng nginx riêng bên ngoài.

### LB cấu hình gì
- **TLS termination** tại LB (HTTPS vào, HTTP/mTLS phía trong).
- **Health check** → chỉ route tới gateway pod `/actuator/health` OK.
- **KHÔNG cần sticky session**: gateway stateless → round-robin thoải mái.
- **Timeout/limit kết nối** ở LB để chắn quá tải sớm. **Không** đặt auth/rate-limit ở LB (để ở gateway).

### Vì sao rate-limit vẫn đúng khi Gateway thành CỤM
Token bucket nằm ở **Redis chung** (key hash-tag), nên dù LB rải request cho N gateway pod, hạn mức
một đối tác vẫn **đúng tổng** — không nhân N. (Nếu để rate-limit cục bộ từng pod thì sẽ ×N → sai.)

### Triển khai trên cụm test (kind) — mô phỏng đúng mô hình
```bash
# 1) Cài ingress-nginx (lớp "nginx LB", chạy nhiều replica)
kubectl apply -f https://raw.githubusercontent.com/kubernetes/ingress-nginx/main/deploy/static/provider/kind/deploy.yaml
kubectl -n ingress-nginx rollout status deploy/ingress-nginx-controller --timeout=180s

# 2) Nâng api-gateway thành CỤM (≥2 pod)
kubectl -n bccs scale deploy/api-gateway --replicas=2
```
```yaml
# 3) Ingress: đối tác gọi host → Service api-gateway (ingress tự chia cho 2 gateway pod)
apiVersion: networking.k8s.io/v1
kind: Ingress
metadata:
  name: bccs-gateway
  namespace: bccs
  annotations:
    nginx.ingress.kubernetes.io/load-balance: "round_robin"
spec:
  ingressClassName: nginx
  rules:
    - host: api.bccs.local            # trỏ DNS/hosts của đối tác vào đây
      http:
        paths:
          - path: /
            pathType: Prefix
            backend:
              service:
                name: api-gateway
                port: { number: 8080 }
```
Kết quả: `Đối tác → ingress-nginx (LB) → cụm gateway (2 pod) → service` — đúng mô hình mục tiêu.

### Production
- ingress-nginx ≥2 replica **sau** một Service `LoadBalancer` (cloud LB) — hoặc 2 nginx + keepalived nếu on-prem.
- TLS bằng cert-manager; bật rate-limit/timeout mức LB như tuyến phòng thủ đầu; tách path đối tác (external) và admin/FE (internal) bằng host/Ingress riêng.

---

> Chi tiết theo giai đoạn & checklist tick được: `LO-TRINH-TRIEN-KHAI-THEO-GIAI-DOAN.md`.
> Chi tiết HA/LB/sizing đã thảo luận: `KE-HOACH-HA-TANG-PRODUCTION.md`, `TONG-HOP-THAO-LUAN-HATANG.txt`.
