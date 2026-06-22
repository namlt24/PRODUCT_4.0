# Chạy thử toàn bộ dự án trên K8s (kind) + DB ngoài — Báo cáo chi tiết

> Ghi lại **đúng những gì đã thực hiện** khi triển khai và kiểm thử toàn bộ hệ thống trên cụm
> kind `bccs-test`, với **MariaDB tách ra ngoài cụm** (chạy bằng Docker trên máy). Kèm giải thích
> **từng cấu hình** và **kết quả test thực tế**.

Ngày chạy: 2026-06-22 · Cụm: `kind-bccs-test` (1 node) · DB: container `bccs-mariadb-ext` (ngoài cụm).

---

## 1. Kết quả tổng quan (PASS)

| Hạng mục | Kết quả |
|---|---|
| 6 pod (gateway, catalog, management, integration, redis, kafka) | ✅ Running 1/1 |
| App trong kind ↔ **MariaDB ngoài** | ✅ `health.db = UP (MariaDB)`, dữ liệu nằm trong container ngoài |
| Xác thực API key (đúng / sai / thiếu / treo) | ✅ 200 / 401 / 401 / 401 |
| Rate-limit động theo đối tác (header + 429) | ✅ override 300/600, tier 40, 429 khi bắn đồng thời |
| Vô hiệu hóa cache qua Kafka (update → đọc lại thấy mới) | ✅ phản ánh trong cửa sổ TTL |
| Sự cố phát hiện & sửa | ✅ xung đột bean `RateLimiter` (đã `@Primary`) |
| Tồn đọng | ⚠️ log app không hiện trên stdout (lỗi logback JSON_CONSOLE — observability) |

---

## 2. Kiến trúc đã chạy

```
                 port-forward 8080
   curl  ────────────────────────▶ api-gateway (pod) ──auth API key──▶ rate-limit (Redis)
                                          │ X-Client-Id + X-Api-Key            │
                                          ▼                                    ▼
                            product-catalog / product-management        [redis pod]
                                          │  JDBC (DB_HOST=mariadb)
                                          ▼
                          Service "mariadb" (ExternalName) ──▶ container bccs-mariadb-ext
                                                                (Docker trên máy, mạng "kind")
        management ──(Kafka catalog.changes)──▶ catalog evict cache   [kafka pod]
```

---

## 3. Các bước đã thực hiện (kèm giải thích lệnh)

### 3.1. Kiểm tra môi trường
```bash
docker version          # 28.1.1 đang chạy
kind get clusters       # bccs-test (đã có sẵn)
kubectl config current-context   # kind-bccs-test
kubectl get nodes       # bccs-test-control-plane Ready
kubectl get ns bccs     # NotFound -> sạch, deploy mới
docker network ls | grep kind    # mạng "kind" tồn tại
```

### 3.2. Build + nạp image vào kind
```bash
docker compose build api-gateway product-catalog-service \
  product-management-service integration-service          # build trong Docker (Java 21)
for img in api-gateway product-catalog-service product-management-service integration-service; do
  kind load docker-image bccs-product-catalog-$img:latest --name bccs-test   # nạp vào node kind
done
```
- `kind load` cần thiết vì cụm kind không tự thấy image trên Docker host. Manifest để
  `imagePullPolicy: IfNotPresent` nên dùng image local vừa nạp, không pull registry.

### 3.3. Dựng MariaDB NGOÀI cụm (trên máy, gắn mạng kind)
```bash
cd scripts/external-db && docker compose up -d
docker inspect -f '...' bccs-mariadb-ext   # kind=172.20.0.7  (đã ở trên mạng kind)
docker exec bccs-mariadb-ext mariadb ... -e "SHOW TABLES;"   # 5 bảng đã tạo từ schema.sql
```
- Container gắn vào **mạng `kind`** để pod gọi tới được; publish cổng host 3307 để dùng tool.
- `schema.sql` mount vào `/docker-entrypoint-initdb.d` → MariaDB tự tạo bảng lần đầu.
- Kết quả: 5 bảng `product, offer, partner, rate_limit_tier, partner_rate_limit` + seed 4 đối tác.

### 3.4. Apply manifest K8s theo thứ tự
```bash
kubectl apply -f k8s/test/00-namespace-config.yaml   # namespace + ConfigMap + Secret (KHÔNG còn schema)
kubectl apply -f k8s/test/05-external-db.yaml         # Service "mariadb" (ExternalName -> bccs-mariadb-ext)
kubectl apply -f k8s/test/10-infra.yaml               # CHỈ Redis + Kafka (MariaDB đã tách)
kubectl -n bccs rollout status deploy/redis deploy/kafka
kubectl apply -f k8s/test/20-services.yaml            # 4 service ứng dụng
```

### 3.5. Sự cố & cách sửa (quan trọng)
**Triệu chứng:** `api-gateway` CrashLoopBackOff, log chỉ có banner rồi exit 1 (lỗi bị nuốt vì
JSON_CONSOLE không in ra). **Cách tìm lỗi:** chạy thẳng image gateway bằng Docker, ép logback
console text → lộ stack trace:
```
APPLICATION FAILED TO START
Parameter 0 of method requestRateLimiterGatewayFilterFactory required a single bean,
but 2 were found: partnerRateLimiter, redisRateLimiter
```
**Nguyên nhân:** Spring Cloud Gateway tự tạo sẵn bean `redisRateLimiter`; cộng với
`partnerRateLimiter` của ta → factory không biết chọn cái nào.
**Sửa:** đánh dấu `PartnerRateLimiter` là `@Primary` → rebuild → `kind load` → `rollout restart`.
(Đây là lỗi **runtime wiring**, build không bắt được.)

---

## 4. Giải thích TỪNG cấu hình

### 4.1. `scripts/external-db/docker-compose.yml` (MariaDB ngoài)
| Mục | Ý nghĩa |
|---|---|
| `container_name: bccs-mariadb-ext` | = `externalName` trong Service K8s → pod phân giải tới đây |
| `networks: [kind]` + `networks.kind.external: true` | gắn vào mạng kind có sẵn để pod gọi được |
| `ports: 3307:3306` | mở cổng host để nối bằng tool (host 3306 thường bận) |
| mount `schema.sql` → `/docker-entrypoint-initdb.d` | tạo bảng lần đầu (một nguồn schema duy nhất) |
| `MARIADB_USER/PASSWORD` = bccs_app/Bccs_App_2026 | **phải khớp** Secret `bccs-secrets` để app nối được |

### 4.2. `k8s/test/00-namespace-config.yaml`
- **Namespace `bccs`**: ngăn chứa toàn bộ dự án.
- **ConfigMap `bccs-config`**: biến không bí mật. Mấu chốt: `DB_HOST: "mariadb"` — app gọi tên này,
  được phân giải ra DB ngoài qua Service ExternalName. `SPRING_PROFILES_ACTIVE: "local"` tắt Logstash.
- **Secret `bccs-secrets`**: `DB_USER/DB_PASSWORD` (khớp DB ngoài), `JWT_PUBLIC_KEY`, `REDIS_PASSWORD`.
- **ĐÃ GỠ** ConfigMap `mariadb-initdb` (schema không còn nhúng ở đây → hết cảnh sửa 2 nơi).

### 4.3. `k8s/test/05-external-db.yaml` (MỚI)
```yaml
kind: Service
spec:
  type: ExternalName
  externalName: bccs-mariadb-ext
```
- Service tên `mariadb` kiểu **ExternalName** → khi pod gọi `mariadb:3306`, K8s trả về CNAME
  `bccs-mariadb-ext`; CoreDNS của kind forward ra Docker DNS → phân giải IP container trên mạng kind.
- Nhờ vậy **app không phải đổi cấu hình** (vẫn `DB_HOST=mariadb`) dù DB nằm ngoài.

### 4.4. `k8s/test/10-infra.yaml`
- **CHỈ còn Redis + Kafka** (đã gỡ MariaDB Deployment+Service).
- Kafka KRaft: `KAFKA_CONTROLLER_QUORUM_VOTERS=1@localhost:9093` (tránh deadlock endpoint single-node).
- Mỗi cái có Deployment (giữ pod chạy) + Service (tên DNS nội bộ `redis`/`kafka`).

### 4.5. `k8s/test/20-services.yaml` (4 service app)
- Mỗi service: **initContainer `wait-deps`** (`nc -z` chờ mariadb/redis/kafka mở cổng) → JVM mới khởi động.
  *Chính bước này xác nhận pod phân giải được tên `mariadb` ra DB ngoài (init pass = DNS + cổng OK).*
- `envFrom`: nạp toàn bộ ConfigMap `bccs-config` + Secret `bccs-secrets`.
- Probe `/actuator/health/readiness` + `liveness`. `api-gateway` Service kiểu **NodePort 30080**.

### 4.6. Sửa code: `PartnerRateLimiter` `@Primary`
```java
@Primary                         // <-- thêm
@Component("partnerRateLimiter")
public class PartnerRateLimiter implements RateLimiter<Config> { ... }
```
Để `RequestRateLimiterGatewayFilterFactory` tiêm đúng 1 bean `RateLimiter` (ưu tiên của ta thay vì
`redisRateLimiter` mặc định). Tham chiếu `#{@partnerRateLimiter}` trong YAML vẫn dùng theo tên.

---

## 5. Kết quả test thực tế

### 5.1. Health (app ↔ DB ngoài + Redis)
```
gateway /actuator/health : status UP, db=UP(MariaDB), redis=UP(7.2.14)
catalog /actuator/health : status UP, db=UP(MariaDB), redis=UP
```

### 5.2. Xác thực API key (qua gateway, cổng 8080)
| Trường hợp | Header | Kết quả |
|---|---|---|
| Key đúng (PARTNER_A) | `X-Client-Id: PARTNER_A` + `X-Api-Key: bccs_ak_partnerA_demo` | **200** |
| Key sai | `X-Api-Key: sai_key` | **401** |
| Thiếu key | (chỉ X-Client-Id) | **401** |
| Đối tác bị treo (PARTNER_X) | key đúng nhưng status SUSPENDED | **401** |

### 5.3. Rate-limit động theo đối tác
Header thực tế khi đọc bằng PARTNER_A trên route catalog:
```
X-RateLimit-Policy-Source: partner:PARTNER_A:product-catalog   <- đúng override theo API
X-RateLimit-Replenish-Rate: 300
X-RateLimit-Burst-Capacity: 600
X-RateLimit-Remaining: 599
```
PARTNER_B (không có override catalog → kế thừa tier BRONZE): `Policy-Source: tier:BRONZE`, rate 20, burst 40.

**Ép 429 bằng 150 request đồng thời (PARTNER_B, burst 40):**
```
200 = 104   429 = 46   khac = 0
```
→ Cho qua ~burst 40 + token nạp thêm trong lúc bắn, phần vượt bị **429**. Đúng thiết kế token bucket.

### 5.4. Vô hiệu hóa cache qua Kafka (end-to-end)
```
B1 đọc lần 1     : name="Goi Data"           (nạp cache)
B2 update (mgmt) : name="Goi Data DA SUA"    (phát event sau commit)
B3 đọc lại (3s)  : name="Goi Data DA SUA"    (thấy NGAY giá trị mới)
```
L1 Caffeine TTL=30s; đọc lại sau 3s ra giá trị mới ⇒ cache **đã bị evict bởi event**, không phải hết hạn.

### 5.5. Bằng chứng dùng đúng DB NGOÀI
```sql
-- truy vấn thẳng container bccs-mariadb-ext:
id                                    code  name
c8ef992d-...                          P001  Goi Cuoc Test
c16d74c4-...                          P002  Goi Data DA SUA   <- update đã ghi xuống DB ngoài
```

---

## 6. Tồn đọng (cần xử lý tiếp)

⚠️ **Log ứng dụng không hiện trên stdout** (`kubectl logs` chỉ thấy banner). Appender `JSON_CONSOLE`
(`LoggingEventCompositeJsonEncoder`) không phát log ra stdout — nên Filebeat/Kibana cũng sẽ không thu
được. **Không ảnh hưởng chức năng** (đã kiểm chứng), nhưng **hỏng observability**. Nghi do
logstash-logback-encoder/logback không tương thích hoặc template provider. → Cần sửa logback-spring.xml
(vd dùng `LogstashEncoder` đơn giản hoặc kiểm tra version) rồi rebuild cả 4 image.

⚠️ Đường ghi (management) hiện vẫn sau gateway/áp rate-limit đối tác — theo thiết kế sẽ tách sang
admin gateway/BFF nội bộ (xem `XAC-THUC-API-KEY-DOI-TAC.md`).

---

## 7. Chạy lại từ đầu (tóm tắt)
```bash
kubectl delete namespace bccs                                   # dọn
cd scripts/external-db && docker compose up -d && cd ../..      # DB ngoài
for img in api-gateway product-catalog-service product-management-service integration-service; do
  kind load docker-image bccs-product-catalog-$img:latest --name bccs-test; done
kubectl apply -f k8s/test/00-namespace-config.yaml
kubectl apply -f k8s/test/05-external-db.yaml
kubectl apply -f k8s/test/10-infra.yaml
kubectl -n bccs rollout status deploy/redis deploy/kafka
kubectl apply -f k8s/test/20-services.yaml
kubectl -n bccs get pods                                        # chờ 6/6 Running
kubectl -n bccs port-forward svc/api-gateway 8080:8080          # rồi curl localhost:8080
```
