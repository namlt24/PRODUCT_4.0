# KỊCH BẢN TEST RESILIENCE — KHI HẠ TẦNG SẬP

> Bộ kịch bản chaos/resilience kiểm chứng yêu cầu **"một cụm hạ tầng chết, hệ thống không sập"**.
> Mỗi ca gồm: Mục tiêu · Cách gây lỗi · Lệnh kiểm chứng · Kết quả kỳ vọng · Khôi phục · Cơ chế code.
>
> Giả định topology test (như đang chạy 2026-06-19): hạ tầng (MariaDB/Redis/Kafka) trong Docker,
> 4 service chạy host (cổng 8080–8083). Gây lỗi hạ tầng = `docker compose stop <infra>`.
> Nếu chạy tất cả trong Docker thì thay 8081→`product-catalog-service:8081` v.v.

## Bảng tổng hợp (kết quả kỳ vọng)

| Ca hỏng | Đọc (catalog) | Ghi (management) | Gateway | Hệ thống sập? |
|---|---|---|---|---|
| **Redis sập** | ✅ vẫn 200 (fail-open DB) | ✅ bình thường | ✅ (rate-limit fail-open) | ❌ Không |
| **Kafka sập** | ✅ vẫn 200 | ✅ commit OK, event hoãn | ✅ | ❌ Không |
| **API Gateway sập** | ✅ gọi thẳng 8081 OK | ✅ gọi thẳng 8082 OK | ❌ cổng vào mất | ❌ Không (service còn) |
| **1 service downstream sập** | — | — | ✅ trả 503 fallback | ❌ Không (cô lập) |
| **Auth bên thứ 3 sập** | — | — | ✅ circuit breaker | ❌ Không |
| **MariaDB sập** | ⚠️ chỉ phục vụ item đã cache | ❌ ghi lỗi (đúng) | ✅ | ⚠️ suy giảm, readiness fail |

---

## CA 1 — REDIS SẬP (đã kiểm chứng thực tế ✅)

**Mục tiêu:** Mất Redis (L2) thì đọc vẫn phục vụ được nhờ **fail-open xuống DB**; pod vẫn READY.

**Gây lỗi:**
```bash
docker compose stop redis
```

**Kiểm chứng:**
```bash
# tạo sản phẩm mới rồi đọc khi redis đang sập
PID=<id vừa tạo>
curl -s -o /dev/null -w "GET -> %{http_code} (%{time_total}s)\n" http://localhost:8081/api/v1/products/$PID
curl -s -o /dev/null -w "readiness -> %{http_code}\n" http://localhost:8081/actuator/health/readiness
```

**Kết quả thật (đo 2026-06-19):**
```
GET (chưa cache, redis down) -> HTTP 200 (0.222s)   # fail-open xuống DB
GET lần 2 (L1 caffeine)      -> HTTP 200 (0.004s)   # phục vụ từ L1
readiness                    -> HTTP 200             # KHÔNG phụ thuộc redis
```

**Khôi phục:** `docker compose start redis`

**Cơ chế code:** `TwoLevelCacheService.safeRedisGet/safeRedisPut` bọc try/catch → lỗi Redis trả
null/bỏ qua, rơi xuống loader DB. Readiness group chỉ gồm `db,ping`
(`management.endpoint.health.group.readiness.include`) nên Redis hỏng không làm pod un-ready.

---

## CA 2 — KAFKA SẬP

**Mục tiêu:** Mất Kafka thì đọc/ghi vẫn chạy; chỉ việc **hủy cache theo sự kiện bị hoãn** (TTL vẫn
là lưới an toàn). Khi Kafka trở lại, consumer resume.

**Gây lỗi:**
```bash
docker compose stop kafka
```

**Kiểm chứng:**
```bash
# Ghi vẫn commit (event sẽ retry/đợi), HTTP trả nhanh
curl -s -X POST http://localhost:8082/api/v1/management/products -H "Content-Type: application/json" \
  -d '{"code":"SP-KAFKA-DOWN-1","name":"Kafka down","category":"FTTH","status":"ACTIVE"}' \
  -w "\ncreate -> %{http_code} (%{time_total}s)\n"
# Đọc vẫn 200
curl -s -o /dev/null -w "read -> %{http_code}\n" http://localhost:8081/api/v1/products/<id>
# Readiness vẫn UP (không phụ thuộc kafka)
curl -s -o /dev/null -w "readiness -> %{http_code}\n" http://localhost:8081/actuator/health/readiness
```

**Kết quả kỳ vọng:**
- `create -> 201` nhanh (publish event là **async** `whenComplete`, không chặn request).
- `read -> 200`. `readiness -> 200`.
- Log management: `Failed to publish catalog change event ...` (producer retry tới
  `delivery.timeout.ms=12000`). Log catalog: consumer cảnh báo mất kết nối, tự reconnect.
- **Lưu ý nhất quán:** nếu sản phẩm đã nằm trong cache, UPDATE lúc Kafka sập sẽ **không evict ngay**
  → đọc có thể thấy giá cũ tới khi Kafka trở lại (event được giao) **hoặc** tới khi TTL hết
  (≤ ~10–12 phút). Đây là *eventual consistency* có chủ đích.

**Khôi phục:** `docker compose start kafka` → producer giao nốt event còn trong delivery timeout,
consumer resume và evict.

**Cơ chế code:** `CatalogEventPublisher.publish` gửi async + chỉ log khi lỗi; producer cấu hình
`acks=all`+idempotence+retry (`KafkaProducerConfig`). Consumer có `DefaultErrorHandler` +
reconnect. TTL ngẫu nhiên ở `TwoLevelCacheService` bao staleness.

---

## CA 3 — API GATEWAY SẬP (góc nhìn ĐỐI TÁC bên ngoài)

> ⚠️ **Quan trọng:** đối tác/bên thứ 3 **chỉ có URL của Gateway** — backend (8081/8082) KHÔNG
> expose ra ngoài (nằm trong mạng nội bộ, không có IP public, không qua auth/rate-limit). Vì vậy
> "gọi thẳng backend" **không phải** giải pháp cho đối tác. Khi một gateway chết, đối tác gọi vào
> **sẽ lỗi NẾU chỉ có 1 gateway**. Giải pháp đúng: **Gateway phải tự HA** — nhiều replica sau một
> Load Balancer; LB chính là URL duy nhất đối tác dùng.

**Mục tiêu:** Một instance gateway chết thì **Load Balancer tự chuyển** request sang instance còn
sống → đối tác **không thấy gián đoạn**. Gateway **stateless** (state rate-limit ở Redis ngoài)
nên replica nào cũng phục vụ được mọi request.

```
Đối tác ─> [Load Balancer  (URL duy nhất, health-check)]
              ├─ api-gateway-1  ✔
              └─ api-gateway-2  ✔   (1 cái chết -> LB loại ra, dồn sang cái còn lại)
```

### 3a. Test HA local (2 gateway + Nginx LB) — chạy được ngay
Đã có sẵn `docker-compose.ha.yml` + `loadbalancer/nginx-gateway-lb.conf`.

**Dựng cụm HA:**
```bash
docker compose -f docker-compose.yml -f docker-compose.ha.yml up -d --build api-gateway-1 api-gateway-2 gateway-lb
```

**Gây lỗi + kiểm chứng (đối tác chỉ gọi vào LB cổng 8888):**
```bash
curl -s -o /dev/null -w "LB truoc khi sap -> %{http_code}\n" http://localhost:8888/actuator/health
docker compose -f docker-compose.yml -f docker-compose.ha.yml stop api-gateway-1     # giết 1 gateway
curl -s -o /dev/null -w "LB sau khi sap 1 gw -> %{http_code}\n" http://localhost:8888/actuator/health
curl -s -o /dev/null -w "lai lan nua          -> %{http_code}\n" http://localhost:8888/actuator/health
```

**Kết quả kỳ vọng:** cả 3 lần đều **200** — đối tác không hề thấy lỗi vì Nginx `proxy_next_upstream`
chuyển sang `api-gateway-2`. Khôi phục: `... start api-gateway-1`.

### 3b. Production (K8s) — cơ chế thật
- **≥3 replica gateway** sau **Service `type: LoadBalancer`/Ingress** (URL/VIP ổn định cho đối tác).
  Xem `api-gateway/k8s/deployment.yaml` (minReplicas=3, `maxUnavailable:0`, topology spread) và
  `service.yaml` (LoadBalancer).
- **Liveness/Readiness probe**: pod treo/chết → K8s tự **loại khỏi Endpoints** + **restart**.
- **Rolling update `maxUnavailable:0`**: deploy phiên bản mới **không** rớt request.
- **Đa AZ / DR**: trải replica nhiều vùng sẵn sàng (availability zone); nâng cao thì multi-region +
  **DNS failover** cho thảm họa cả cụm.
- **Phía đối tác**: khuyến nghị họ **retry + backoff** và idempotency để mượt qua các blip ngắn.

> Tóm lại: với đối tác, "Gateway sập" **không** được giải bằng bypass backend, mà bằng **làm cho
> chính Gateway không bao giờ chỉ có một bản** (N replica + LB). SPOF bị loại bỏ ở tầng cổng vào.

**Bypass backend chỉ dùng nội bộ/dev:** trong mạng nội bộ, đội vận hành có thể gọi thẳng 8081/8082
để chẩn đoán — nhưng đây KHÔNG phải đường đi của đối tác.

---

## CA 4 — MỘT SERVICE DOWNSTREAM SẬP (cô lập lỗi qua Circuit Breaker)

**Mục tiêu:** Khi catalog (hoặc management) sập, Gateway **không treo/sập dây chuyền** mà trả
**503 fallback có kiểm soát**; các route khác vẫn hoạt động.

**Gây lỗi:** dừng product-catalog-service (để gateway còn sống).

**Kiểm chứng (qua gateway, cần Bearer token):**
```bash
curl -s http://localhost:8080/api/v1/products/<id> -H "Authorization: Bearer <token>" -w "\n%{http_code}\n"
```

**Kết quả kỳ vọng:**
```json
{"status":503,"error":"Service Unavailable","message":"product-catalog-service temporarily unavailable","path":"/fallback/catalog"}
```
Header `Retry-After: 5`. Route `/api/v1/management/**` vẫn 200 (lỗi được **cô lập theo route**).

**Cơ chế code:** filter `CircuitBreaker` trong `api-gateway/application.yml`
(`fallbackUri: forward:/fallback/catalog`) → `FallbackController` trả 503. Breaker mở sau khi
vượt `failure-rate-threshold` để **fail fast** thay vì treo.

> Đây chính là 503 bạn đã gặp khi gateway-on-host trỏ nhầm `CATALOG_URI` — cùng cơ chế.

---

## CA 5 — AUTH BÊN THỨ 3 SẬP (Resilience4j Circuit Breaker)

**Mục tiêu:** Cổng Auth bên thứ 3 chậm/sập thì **không treo gateway**; breaker mở, trả phản hồi
nhanh.

**Điều kiện:** chạy ở chế độ introspection (không set `JWT_PUBLIC_KEY`), `THIRD_PARTY_AUTH_URL`
trỏ một địa chỉ không phản hồi.

**Kiểm chứng:**
```bash
# bắn nhiều request có token để mở breaker
for i in $(seq 1 15); do curl -s -o /dev/null -w "%{http_code} " http://localhost:8080/api/v1/products/x -H "Authorization: Bearer faketoken"; done; echo
# integration-service trả nhanh active=false thay vì treo 2s mỗi lần
curl -s http://localhost:8083/api/v1/integration/validate -H "Authorization: Bearer faketoken" -w "\n%{http_code}\n"
```

**Kết quả kỳ vọng:** sau vài lần lỗi/chậm, breaker `thirdPartyAuth`/`authProvider` **OPEN** →
`ThirdPartyAuthClient.fallback` trả `{active:false, error:auth_provider_unavailable}` **ngay lập
tức** (không chờ timeout 2s). Gateway trả 401 nhanh, không cạn thread.

**Cơ chế code:** `@CircuitBreaker(name="thirdPartyAuth", fallbackMethod="fallback")` +`@Retry` ở
`ThirdPartyAuthClient`; cấu hình ngưỡng trong `integration-service/application.yml` &
`api-gateway` (`resilience4j.circuitbreaker.instances.authProvider`).

---

## CA 6 — MARIADB SẬP

**Mục tiêu:** Làm rõ giới hạn: DB là nguồn sự thật, mất DB thì **ghi phải lỗi** (đúng), **đọc chỉ
phục vụ được item còn trong cache**; readiness sẽ fail để LB ngừng đẩy traffic.

**Gây lỗi:**
```bash
docker compose stop mariadb
```

**Kiểm chứng:**
```bash
# đọc item ĐÃ cache trước đó -> vẫn 200 (từ L1/L2)
curl -s -o /dev/null -w "read item da cache -> %{http_code}\n" http://localhost:8081/api/v1/products/<id-da-cache>
# đọc item CHƯA cache -> lỗi (phải chạm DB)
curl -s -o /dev/null -w "read item moi -> %{http_code}\n" http://localhost:8081/api/v1/products/<id-la>
# ghi -> lỗi (đúng kỳ vọng)
curl -s -o /dev/null -w "write -> %{http_code}\n" -X POST http://localhost:8082/api/v1/management/products \
  -H "Content-Type: application/json" -d '{"code":"X","name":"X","status":"ACTIVE"}'
# readiness -> DOWN (db nằm trong nhóm readiness)
curl -s -o /dev/null -w "readiness -> %{http_code}\n" http://localhost:8081/actuator/health/readiness
```

**Kết quả kỳ vọng:** item đã cache → 200; item mới → 500/503; ghi → 5xx; **readiness → 503** →
K8s ngừng route traffic vào pod tới khi DB hồi (đây là hành vi đúng: bảo vệ, không phục vụ sai).

**Khôi phục:** `docker compose start mariadb`. Production: HA DB (replica/Galera) + backup hằng
ngày (`scripts/db-backup/`) để rollback.

---

## Cách chạy nhanh toàn bộ (gợi ý)

1. Đảm bảo 4 service + infra đang chạy, đã có 1 `productId` đã được đọc (đang nằm cache).
2. Chạy lần lượt CA 1 → CA 6, mỗi ca: gây lỗi → kiểm chứng → khôi phục, ghi lại HTTP code.
3. Đối chiếu với cột "Kết quả kỳ vọng".

> Khuyến nghị khi bảo vệ hội đồng: diễn **trực tiếp CA 1 (Redis)** và **CA 4 (downstream → 503)**
> — hai ca này chứng minh rõ nhất "không sập" và "cô lập lỗi".
