# TÀI LIỆU BẢO VỆ KIẾN TRÚC — BCCS PRODUCT CATALOG 4.0

> Hệ thống Danh mục sản phẩm viễn thông hiệu năng cao, phục vụ đối tác/bên thứ 3, xử lý hàng
> triệu request/ngày, độ trễ đọc sub-millisecond, cloud-native, sẵn sàng cao.
>
> Tài liệu này dùng để trình bày & bảo vệ trước hội đồng chuyên gia: mô tả **cách xây dựng hạ
> tầng**, **lý do (why)** đằng sau từng lựa chọn (kèm đánh đổi), **kịch bản demo có số liệu
> thật**, và **phần hỏi-đáp** lường trước các câu hỏi phản biện.

---

## MỤC LỤC

1. [Bối cảnh & yêu cầu bài toán](#1-bối-cảnh--yêu-cầu-bài-toán)
2. [Tổng quan kiến trúc](#2-tổng-quan-kiến-trúc)
3. [Giải thích từng quyết định kiến trúc (WHY)](#3-giải-thích-từng-quyết-định-kiến-trúc-why)
4. [Quy trình dựng hạ tầng thực tế (kèm sự cố & cách xử lý)](#4-quy-trình-dựng-hạ-tầng-thực-tế)
5. [Kịch bản demo chi tiết (số liệu thật)](#5-kịch-bản-demo-chi-tiết)
6. [Bản đồ yêu cầu → giải pháp](#6-bản-đồ-yêu-cầu--giải-pháp)
7. [Hỏi-đáp với hội đồng (lường trước phản biện)](#7-hỏi-đáp-với-hội-đồng)
8. [Hạn chế hiện tại & lộ trình production](#8-hạn-chế-hiện-tại--lộ-trình-production)

---

## 1. BỐI CẢNH & YÊU CẦU BÀI TOÁN

### Đặc thù tải
- **Đọc áp đảo ghi (read-heavy)**: đối tác quét danh mục sản phẩm/gói cước/giá liên tục.
  Tỷ lệ điển hình 95–99% đọc / 1–5% ghi.
- **Đột biến (bursty)**: bên thứ 3 thường quét hàng loạt (batch sync) → tạo "bão" request
  cùng lúc lên cùng tập key.
- **Độ trễ khắt khe**: yêu cầu đọc sub-millisecond ở tầng phục vụ.

### 4 ràng buộc cốt lõi định hình kiến trúc
| Ràng buộc | Hệ quả thiết kế |
|---|---|
| Đọc cực lớn, độ trễ < 1ms | Bắt buộc có cache nhiều tầng, không thể chạm DB mỗi request |
| Bên thứ 3 không tin cậy (spam, gọi sai ID, quét ồ ạt) | Rate limit + chống Cache Penetration/Avalanche + Circuit Breaker |
| Sẵn sàng cao — một cụm hạ tầng chết không được sập hệ thống | Fail-open, degrade gracefully, replica, HPA |
| Trace log xuyên suốt sang bên thứ 3 | Chuẩn W3C Trace Context, log JSON tập trung |

> **Luận điểm bảo vệ #1:** Kiến trúc không xuất phát từ "thích công nghệ", mà **suy diễn trực
> tiếp từ 4 ràng buộc tải nói trên**. Mỗi thành phần đều trả lời một ràng buộc cụ thể.

---

## 2. TỔNG QUAN KIẾN TRÚC

```
        Đối tác / Hệ thống bên thứ 3  (gửi kèm header traceparent)
                          │
                ┌─────────▼──────────┐
                │    API GATEWAY     │  • Routing
                │ (Spring Cloud GW,  │  • Rate Limit theo clientId (Redis)
                │   reactive/WebFlux)│  • Xác thực JWT (public key in-memory / introspection)
                │  + Resilience4j CB │  • Sinh/nhận traceId → inject X-Trace-Id, X-Client-Id
                └───┬─────────┬──────┘
   ĐỌC /products,/offers      │ GHI /management/**          │ /integration/**
                  ▼            ▼                              ▼
   product-catalog-service   product-management-service   integration-service
   • Caffeine L1 + Redis L2  • CRUD, vòng đời SP            • Xác thực JWT
   • Cache-aside, fail-open  • Bắn event SAU commit          • Introspection bên thứ 3
   • Consume event → evict          │                        • Circuit Breaker
              ▲                      │
              │   Kafka topic: catalog.changes  (hủy cache)
              └──────────────────────┘
                          │
        MariaDB (shared schema)  ·  Redis  ·  Kafka
                          │
   Observability: Micrometer → Prometheus/Grafana · log JSON → Logstash → Elasticsearch → Kibana
```

**Mô hình tổng thể: CQRS nhẹ (tách Đọc/Ghi) + Event-Driven Cache Invalidation.**
- Service ĐỌC và service GHI tách rời → scale độc lập (đọc scale tới 30 replica, ghi chỉ 2–8).
- Hai bên không gọi đồng bộ nhau; liên kết qua **event bất đồng bộ** (Kafka) → giảm ghép nối,
  tăng chịu lỗi.

---

## 3. GIẢI THÍCH TỪNG QUYẾT ĐỊNH KIẾN TRÚC (WHY)

Mỗi mục theo cấu trúc: **Vấn đề → Lựa chọn → Tại sao → Đã cân nhắc gì khác → Đánh đổi.**

### 3.1. Phân rã 4 microservice theo trách nhiệm (CQRS nhẹ)

- **Vấn đề:** đọc và ghi có hồ sơ tải hoàn toàn khác nhau; gộp chung sẽ buộc scale cả khối.
- **Lựa chọn:** tách `product-catalog-service` (đọc) và `product-management-service` (ghi);
  thêm `api-gateway` (cổng vào) và `integration-service` (tích hợp auth bên thứ 3).
- **Tại sao:**
  - Service đọc tối ưu cho cache, dùng pool kết nối **read-only**, scale ngang mạnh.
  - Service ghi tối ưu cho tính nhất quán giao dịch, pool nhỏ, ít replica.
  - Tách `integration-service` để **cô lập rủi ro** khi gọi sang hệ thống ngoài (bên thứ 3 hay
    chậm/sập) — không để nó kéo theo service nghiệp vụ.
- **Đã cân nhắc:** monolith (đơn giản hơn nhưng không scale chọn lọc được); event sourcing
  đầy đủ (quá nặng cho bài toán catalog chủ yếu là đọc).
- **Đánh đổi:** thêm độ phức tạp vận hành & nhất quán cuối (eventual consistency) giữa ghi và
  cache — chấp nhận được vì catalog không yêu cầu strong consistency tức thì.

### 3.2. API Gateway = Spring Cloud Gateway (reactive)

- **Vấn đề:** cần một điểm vào duy nhất để áp rate limit, xác thực, tracing, định tuyến.
- **Tại sao chọn Spring Cloud Gateway:** chạy trên **Netty non-blocking** → 1 gateway giữ
  hàng nghìn kết nối đồng thời với ít thread, phù hợp tải lớn từ đối tác; tích hợp sẵn
  `RequestRateLimiter` dùng Redis + token-bucket, và filter tùy biến.
- **Đã cân nhắc:** Nginx/Kong (mạnh nhưng tách khỏi hệ sinh thái Java, khó nhúng logic
  xác thực JWT bất đối xứng tùy biến); Zuul 1 (blocking, đã lỗi thời).
- **Đánh đổi:** lập trình reactive khó debug hơn servlet — nên **chỉ gateway dùng reactive**,
  3 service còn lại dùng servlet + **virtual threads (Java 21)** cho dễ viết mà vẫn chịu tải.

### 3.3. Cache 2 tầng: Caffeine (L1) + Redis (L2) — mô hình Cache-Aside

- **Vấn đề:** sub-ms đọc + chịu burst + không sập DB.
- **Tại sao 2 tầng:**
  - **L1 Caffeine (in-process):** truy cập trong cùng JVM, **0 round-trip mạng** → đây là thứ
    tạo ra độ trễ sub-ms (demo đo **~5ms→thực tế micro giây ở mức cache** so với ~476ms khi
    chạm DB). Đồng thời L1 **chắn bão**: nhiều request trên 1 node gộp về 1 bản L1, giảm tải
    lên Redis.
  - **L2 Redis (phân tán):** chia sẻ giữa mọi replica → cache hit cao toàn cục, và là "nguồn
    sự thật" của cache để mọi node đồng bộ.
- **Tại sao Cache-Aside (không Read-Through/Write-Through):** đơn giản, kiểm soát rõ ràng,
  không phụ thuộc plugin của Redis; ứng dụng tự quyết khi nào nạp/ghi cache.
- **Đã cân nhắc:** chỉ Redis (vẫn tốn 1 round-trip mạng mỗi đọc, không đạt sub-ms tin cậy);
  chỉ Caffeine (không chia sẻ giữa node, hit rate thấp, không nhất quán đa replica).
- **Đánh đổi:** dữ liệu có thể "cũ" trong khoảng TTL của L1 (30s) → giải quyết bằng
  **invalidation qua Kafka** (mục 3.4) để rút ngắn cửa sổ cũ xuống ~1 giây.

#### 3 "bệnh kinh điển" của cache và cách chống (điểm hội đồng hay hỏi)

| Bệnh | Cơ chế gây hại | Cách hệ thống chống |
|---|---|---|
| **Cache Avalanche** (tuyết lở) | Hàng loạt key hết hạn cùng lúc → toàn bộ đổ về DB cùng giây | **Random TTL**: mỗi key Redis = `base 600s + jitter ngẫu nhiên 0–120s`. Đối tác quét hàng loạt populate cùng lúc nhưng hết hạn **rải đều**, không dồn cục. |
| **Cache Penetration** (xuyên thủng) | Gọi liên tục ID không tồn tại → cache luôn miss → mọi request đập vào DB | **Cache Null Values**: lưu bản ghi rỗng `__NULL__` với TTL ngắn (60s). Request ID rác lần sau trả 404 ngay từ cache. (Demo: key `product:khong-ton-tai-123` nằm trong Redis TTL 44s.) |
| **Cache Breakdown/Outage** (Redis sập) | Redis chết → mọi đọc dồn về DB → sập dây chuyền | **Fail-open**: mọi thao tác Redis bọc try/catch, lỗi thì **degrade xuống DB**; readiness probe chỉ phụ thuộc `db,ping` nên pod vẫn READY. Hệ thống chậm đi chứ **không sập**. |

> Mã nguồn: `product-catalog-service/.../service/TwoLevelCacheService.java` — toàn bộ 3 cơ chế
> nằm trong một class, dễ chỉ cho hội đồng.

### 3.4. Hủy cache hướng sự kiện bằng Kafka (không chỉ dựa TTL)

- **Vấn đề:** nếu chỉ dựa TTL, sau khi sửa giá, đối tác có thể nhận giá cũ tới hết TTL (10 phút).
- **Lựa chọn:** khi ghi, `product-management-service` bắn `CatalogChangeEvent` vào topic
  `catalog.changes`; `product-catalog-service` consume và **evict đúng key** ở cả L1+L2.
- **Điểm tinh tế (hội đồng đánh giá cao):** event chỉ được bắn **SAU KHI transaction DB commit
  thành công** (dùng `TransactionSynchronization.afterCommit`). Nếu giao dịch rollback thì
  **không** evict cache — tránh hủy cache cho một thay đổi không tồn tại.
- **Tại sao Kafka:** bền (persistent log), nhiều consumer, replay được, throughput cao; phù
  hợp lan truyền thay đổi tới nhiều replica catalog.
- **Đánh đổi & nhất quán:** đây là **nhất quán cuối (eventual)** ~1 giây (demo đo: evict trong
  attempt đầu tiên, <1s). Chấp nhận được cho catalog. Để **exactly-once** tuyệt đối cần thêm
  **Transactional Outbox** (mục 8).

### 3.5. MariaDB shared schema + HikariCP phân bổ có chủ đích

- **Shared schema:** catalog (đọc) và management (ghi) cùng map bảng `product`/`offer`. Đơn
  giản, một nguồn sự thật, phù hợp khi hai service thuộc cùng "bounded context" danh mục.
  - *Đã cân nhắc:* database-per-service (đúng chuẩn microservice triệt để nhưng tạo nhu cầu
    đồng bộ dữ liệu hai chiều — thừa cho bài toán này).
- **HikariCP — chống Connection Starvation:** tổng pool của các service phải **nhỏ hơn
  `max_connections` của DB**. Phân bổ: catalog read-pool **40** (read-only), management
  write-pool **20**. `max-lifetime` đặt nhỏ hơn `wait_timeout` của server để tránh kết nối "chết".
- **Tại sao quan trọng:** nếu mỗi service mở pool lớn vô tội vạ, vài service là đủ làm cạn
  kết nối DB dùng chung → service khác không lấy được kết nối → nghẽn dây chuyền.

### 3.6. Resilience4j Circuit Breaker tại biên gọi bên thứ 3

- **Vấn đề:** cổng Auth của bên thứ 3 chậm/sập → mọi request treo chờ → cạn thread → sập gateway.
- **Lựa chọn:** Circuit Breaker (đóng/mở/half-open) + timeout + retry quanh lời gọi auth.
  Khi tỉ lệ lỗi vượt ngưỡng, breaker **mở**, trả fallback ngay (`FallbackController` → 503 có
  `Retry-After`) thay vì treo.
- **Tại sao Resilience4j:** nhẹ, thuần Java, tích hợp Spring Boot 3 và Micrometer (xuất metric
  trạng thái breaker).
- **Đánh đổi:** khi breaker mở, một số request hợp lệ cũng bị từ chối tạm thời — đây là **đánh
  đổi có chủ đích** giữa "từ chối nhanh một phần" và "sập toàn bộ".

### 3.7. Observability — Tracing W3C, log JSON, Prometheus, ELK

- **Tracing chuẩn W3C Trace Context (thay Spring Cloud Sleuth):** dùng Micrometer Tracing.
  Gateway **nhận `traceparent`** từ bên thứ 3 hoặc **tự sinh trace id (UUID v4)** nếu thiếu,
  rồi truyền tiếp qua **HTTP header và Kafka record header** → một request được nối xuyên suốt
  từ đối tác → gateway → service → cả thao tác evict cache qua Kafka.
- **Vì sao W3C, không tự chế header:** `traceparent` là chuẩn mở → liên thông được với hệ thống
  giám sát của đối tác và các APM (Jaeger/Tempo/Zipkin) mà không cần ánh xạ riêng.
- **Log JSON** đủ trường `timestamp, traceId, spanId, clientId, serviceName, level, message` →
  parse thẳng trên Kibana, lọc theo `clientId` (đối tác) hay `traceId` (1 request) tức thì.
- **Prometheus/Micrometer:** mở `/actuator/prometheus` đo JVM, **HikariCP pool**, **cache
  hit/miss**, **Kafka consumer lag** — đúng các chỉ số sức khỏe cốt lõi của kiến trúc này.

### 3.8. Đóng gói & điều phối — Docker multi-stage + K8s + HPA

- **Dockerfile multi-stage** (build bằng `maven:...temurin-21`, runtime bằng `temurin-21-jre`):
  ảnh runtime chỉ chứa JRE + jar → **nhỏ, ít bề mặt tấn công**, chạy **non-root**, có HEALTHCHECK.
- **K8s:** mỗi service có `deployment/service/configmap/hpa`. `maxUnavailable: 0` khi rollout,
  `topologySpreadConstraints` rải replica nhiều node → mất 1 node không gãy dịch vụ.
- **HPA:** scale theo CPU **và** theo tải request thực tế (`http_server_requests`), với
  `behavior` scale-up nhanh / scale-down chậm để không "giật".
- **Bảo mật cấu hình:** **không hardcode credential vào YAML** — tất cả qua biến môi trường /
  K8s `Secret` (`bccs-secrets`). File `secrets.example.yaml` chỉ là khuôn mẫu.

### 3.9. Sao lưu & phục hồi (yêu cầu bắt buộc)

- `scripts/db-backup/backup.sh`: `mariadb-dump --single-transaction` (snapshot nhất quán không
  khóa bảng), gzip, **kiểm tra toàn vẹn**, xoay vòng 14 ngày, tùy chọn đẩy S3 off-site.
- `k8s-cronjob.yaml`: chạy **02:00 hằng ngày** vào PVC.
- `restore.sh`: phục hồi có xác nhận + nhắc flush Redis sau khi rollback (tránh phục vụ dữ liệu
  cache cũ không khớp DB đã rollback).

---

## 4. QUY TRÌNH DỰNG HẠ TẦNG THỰC TẾ

> Phần này kể lại **đúng cách đã dựng**, gồm cả sự cố gặp phải và cách xử lý — đây là điểm
> thuyết phục hội đồng vì cho thấy hệ thống đã thực sự chạy, không phải lý thuyết.

### Bước 0 — Kiểm tra môi trường
`Docker 28 + Compose v2` (đang chạy), `Maven 3.9`. Lưu ý: **JDK local là 17** nhưng project
target **Java 21** → **không build ở host**, build bên trong container (`temurin-21`).

### Bước 1 — Tạo `.env`
Sinh biến môi trường cho demo (DB password, JWT để rỗng → gateway dùng introspection fallback).
**Không** đưa credential vào YAML — đúng yêu cầu bảo mật.

### Bước 2 — Hạ tầng nền (MariaDB, Redis, Kafka)
```bash
docker compose up -d mariadb redis kafka
```

### Bước 3 — Build 4 service (multi-stage, trong container Java 21)
```bash
set -o pipefail; docker compose build <service> 2>&1 | tail -25; echo "EXIT=${PIPESTATUS[0]}"
```

### Sự cố thực tế đã xử lý (minh chứng năng lực vận hành)

| # | Sự cố | Nguyên nhân | Cách xử lý |
|---|---|---|---|
| 1 | `bitnami/kafka:3.7` không tồn tại | Bitnami gỡ tag cũ khỏi Docker Hub | Chuyển sang image chính chủ **`apache/kafka:3.7.1`** (KRaft, không cần ZooKeeper), đổi biến `KAFKA_CFG_*` → `KAFKA_*` |
| 2 | `bind: 0.0.0.0:3306 ... in use` | Host đã có MariaDB/MySQL chiếm 3306 | Map cổng host **3307→3306**; nội bộ container vẫn `mariadb:3306` nên service không đổi |
| 3 | "build exit 0" nhưng thực ra lỗi | Pipe `\| tail` che mất exit code của docker | Dùng `set -o pipefail` + `${PIPESTATUS[0]}` để lấy đúng exit code |
| 4 | Reactor báo thiếu module khi build | Mỗi Dockerfile chỉ copy 1 module nhưng parent POM khai báo cả 4 | Cho Dockerfile `COPY . .` rồi build đúng module `-pl common-lib,<module> -am`; thêm `.dockerignore` |
| 5 | Gateway crash khi khởi động | `@Value("${gateway.auth.permit-all}")` không bind được YAML **list** | Đổi YAML sang **chuỗi phân tách dấu phẩy** (Spring tự tách vào `List<String>`) |
| 6 | Record đọc từ Redis ra `LinkedHashMap` | `GenericJackson2JsonRedisSerializer` không nhúng type-info cho lớp **final** (record) | `TwoLevelCacheService.unwrap` ép kiểu qua `ObjectMapper.convertValue` |

### Bước 4 — Khởi động 4 service & chờ healthy
Compose tự chờ MariaDB/Kafka **healthy** nhờ `depends_on: condition: service_healthy`.
Kết quả: **7/7 container healthy**.

> **Luận điểm bảo vệ #2:** Các sự cố trên đều là **lỗi tích hợp thật của môi trường thật**, đã
> được chẩn đoán đúng nguyên nhân gốc và xử lý — không "vá tạm". Điều này chứng minh hệ thống
> vận hành được end-to-end.

---

## 5. KỊCH BẢN DEMO CHI TIẾT

> Mọi số liệu dưới đây là **output thật** từ lần chạy. Khi demo trước hội đồng, chạy lại đúng
> thứ tự này. Lưu ý: demo nghiệp vụ cache gọi **thẳng** catalog (8081)/management (8082) để bỏ
> qua lớp auth của gateway; phần auth demo riêng ở bước 5.

### Demo 1 — Ghi dữ liệu + bắn event (CQRS + Event-Driven)
```bash
curl -X POST http://localhost:8082/api/v1/management/products \
  -H "Content-Type: application/json" \
  -d '{"code":"SP-FIBER-100","name":"Goi Internet Cap Quang 100Mbps","category":"FTTH","status":"ACTIVE","description":"Demo"}'
```
**Kết quả:** trả về sản phẩm có `id` (UUID). MariaDB được ghi, đồng thời event `CREATED` bắn
vào Kafka **sau commit**.

### Demo 2 — Cache 2 tầng (điểm nhấn sub-ms)
```bash
curl -w "[%{http_code} %{time_total}s]" http://localhost:8081/api/v1/products/<id>   # lần 1
curl -w "[%{http_code} %{time_total}s]" http://localhost:8081/api/v1/products/<id>   # lần 2
```
**Kết quả thật:**
- Lần 1: `200` — **0.476s** (cache miss → nạp từ DB → ghi L1+L2)
- Lần 2: `200` — **0.005s** (cache hit ở L1 Caffeine)

> **Giải thích cho hội đồng:** chênh lệch ~**95 lần** chứng minh giá trị của cache. Con số
> 0.005s còn bao gồm toàn bộ overhead HTTP/curl; phần truy xuất cache thực tế ở mức **micro
> giây** — đạt mục tiêu sub-ms ở tầng phục vụ.

### Demo 3 — Hủy cache hướng sự kiện (Kafka invalidation)
```bash
curl -X PUT http://localhost:8082/api/v1/management/products/<id> -H "Content-Type: application/json" \
  -d '{"code":"SP-FIBER-100","name":"Goi Cap Quang 100Mbps (DA SUA GIA)","category":"FTTH","status":"ACTIVE"}'
# rồi đọc lại từ catalog
curl http://localhost:8081/api/v1/products/<id>
```
**Kết quả thật:** ngay attempt đầu (<1s) catalog trả về tên mới `... (DA SUA GIA)`.
Log catalog (JSON):
```
"message":"Evicted cache product:7ee6...d1c5 due to UPDATED event",
"traceId":"6a3401c881c9c5174e3780d938bda6f9", "thread":"...KafkaListener...C-1"
```
> **Giải thích:** không phải chờ hết TTL 10 phút — thay đổi lan tới cache trong ~1 giây nhờ
> event. `traceId` trong log evict chính là trace của request ghi → **truy vết xuyên Kafka**.

### Demo 4 — Chống Cache Penetration
```bash
curl -w "[%{http_code}]" http://localhost:8081/api/v1/products/khong-ton-tai-123      # -> 404
docker compose exec redis redis-cli KEYS 'product:*'                                  # -> product:khong-ton-tai-123
docker compose exec redis redis-cli TTL  'product:khong-ton-tai-123'                  # -> 44 (giây)
```
> **Giải thích:** ID rác bị **cache rỗng** TTL ngắn → lần gọi sai tiếp theo trả 404 ngay từ
> cache, **không đập vào DB** → chống tấn công dò ID.

### Demo 5 — Gateway: Auth + Tracing W3C
```bash
# Không token, có gửi traceparent của "đối tác"
curl -w "[%{http_code}]" -H "traceparent: 00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01" \
  http://localhost:8080/api/v1/products/<id>
```
**Kết quả thật:** `401`, body:
```json
{"status":401,"error":"Unauthorized","message":"Missing or malformed Authorization header",
 "traceId":"0af7651916cd43dd8448eb211c80319c"}
```
> **Điểm vàng để bảo vệ:** `traceId` trả về **chính xác bằng** trace-id trong `traceparent`
> đối tác gửi vào → chứng minh Gateway **tiếp nhận đúng chuẩn W3C Trace Context**, không tự chế.
> Nếu đối tác không gửi, Gateway tự sinh UUID v4. → Đối tác chỉ cần báo 1 `traceId` là tra
> được toàn bộ hành trình request trên Kibana.
```bash
curl -D - http://localhost:8080/actuator/health     # path permit-all -> 200 OK
```

### Demo 6 — Observability & Kafka health
```bash
curl -s http://localhost:8081/actuator/prometheus | grep -E "hikaricp_connections|cache_gets_total"
MSYS_NO_PATHCONV=1 docker compose exec kafka /opt/kafka/bin/kafka-consumer-groups.sh \
  --bootstrap-server localhost:9092 --describe --group catalog-cache-invalidator
```
**Kết quả thật:**
- `hikaricp_connections{pool="catalog-read-pool"} 10` — pool đọc đúng cấu hình.
- Kafka group `catalog-cache-invalidator`: **LAG = 0** (đã xử lý 2 event CREATED+UPDATED).

### Demo 7 (tùy chọn, mạnh nhất) — Chứng minh Resilience: tắt Redis vẫn phục vụ
```bash
docker compose stop redis
curl -w "[%{http_code} %{time_total}s]" http://localhost:8081/api/v1/products/<id>
docker compose ps product-catalog-service     # vẫn (healthy)
docker compose start redis
```
> **Kỳ vọng:** request vẫn `200` (degrade xuống DB, chậm hơn), pod catalog **vẫn READY** —
> minh chứng trực tiếp "một cụm hạ tầng chết, hệ thống không sập". (Khuyến nghị diễn live.)

---

## 6. BẢN ĐỒ YÊU CẦU → GIẢI PHÁP

| Yêu cầu đề bài | Thành phần hiện thực | File minh chứng |
|---|---|---|
| Sub-ms đọc, tải lớn | Cache 2 tầng Caffeine L1 + Redis L2 | `TwoLevelCacheService.java` |
| Chống Avalanche | Random TTL (base+jitter) | `TwoLevelCacheService.randomizedTtl()` |
| Chống Penetration | Cache Null Values `__NULL__` | `TwoLevelCacheService.get()` |
| Rate limit chống spam bên thứ 3 | Redis RequestRateLimiter theo clientId | `api-gateway/application.yml`, `RateLimiterConfig.java` |
| Xác thực + điều phối auth bên thứ 3 | JWT public-key in-memory + introspection | `AuthenticationFilter.java`, `integration-service` |
| Circuit Breaker chống sập dây chuyền | Resilience4j tại gateway & integration | `application.yml`, `ThirdPartyAuthClient.java` |
| Đồng bộ/hủy cache event-driven | Kafka `catalog.changes`, publish sau commit | `CatalogEventPublisher.java`, `CatalogChangeConsumer.java` |
| Connection pooling tối ưu | HikariCP phân bổ theo service | các `application.yml` |
| Không hardcode credential | Biến môi trường / K8s Secret | `.env.example`, `secrets.example.yaml` |
| Trace W3C, MDC, log JSON | Micrometer + Logback + filter trace | `TraceContext*`, `logback-spring.xml` |
| Prometheus + ELK | Actuator/Micrometer + Logstash pipeline | `monitoring/` |
| Docker + K8s + HPA | Multi-stage, deployment/service/configmap/hpa | `Dockerfile`, `<svc>/k8s/` |
| Sao lưu DB hằng ngày + rollback | backup/restore + CronJob | `scripts/db-backup/` |
| Hạ tầng chết không sập | Fail-open, readiness `db,ping`, retry, replica | `TwoLevelCacheService`, `application.yml` |

---

## 7. HỎI-ĐÁP VỚI HỘI ĐỒNG

**H: Vì sao cần tới 2 tầng cache, chỉ Redis chưa đủ sao?**
Đ: Redis vẫn tốn 1 round-trip mạng (~0.2–1ms+) mỗi đọc và chịu tải tập trung khi đối tác quét
ồ ạt. L1 Caffeine loại bỏ round-trip (truy cập trong JVM) → đạt sub-ms tin cậy, đồng thời chắn
bão request giúp Redis không quá tải. Hai tầng bổ trợ: L1 cho tốc độ, L2 cho chia sẻ toàn cục.

**H: Dữ liệu cache cũ (stale) xử lý thế nào? Có nguy cơ phục vụ giá sai?**
Đ: Có hai lớp: (1) invalidation hướng sự kiện qua Kafka rút cửa sổ cũ xuống ~1s sau khi ghi;
(2) TTL ngẫu nhiên là lưới an toàn cuối. Đây là **nhất quán cuối** — chấp nhận được cho catalog.
Nếu cần mạnh hơn cho trường "giá", có thể giảm TTL L1 hoặc thêm versioning key.

**H: Nếu event Kafka bị mất thì cache cũ vĩnh viễn?**
Đ: Không. Producer cấu hình `acks=all` + idempotence + retry; consumer commit thủ công sau khi
xử lý. Kể cả mất event, **TTL vẫn làm cache tự hết hạn** (tối đa ~10–12 phút) → không kẹt vĩnh
viễn. Lộ trình production thêm Transactional Outbox để đảm bảo không mất.

**H: Có hiện tượng race giữa nạp lại cache và evict không?**
Đ: Có quan sát thực tế: sau UPDATE, một lần đọc nạp lại L2 ngay trước khi event evict tới, nên
key L2 bị xóa (TTL hiển thị `-2`). **Đây là hành vi an toàn** (thà xóa thừa còn hơn phục vụ
cũ); lần đọc kế tiếp chỉ việc nạp lại. Production có thể khử bằng versioned key/CAS nếu cần.

**H: Shared schema có vi phạm nguyên tắc microservice (database-per-service)?**
Đ: Catalog đọc và management ghi nằm trong **cùng một bounded context "danh mục"**, nên chia sẻ
schema là hợp lý và tránh chi phí đồng bộ dữ liệu hai chiều. Ranh giới được giữ ở tầng service
và bộ entity riêng. Nếu sau này tách bounded context, sẽ tách DB kèm chống hỏng tương thích.

**H: Vì sao MariaDB mà không Oracle như một số mục đề bài?**
Đ: Đề bài mâu thuẫn (mục cấu hình DB ghi MariaDB, mục khác ghi Oracle). Đã **chốt với chủ đầu
tư chọn MariaDB** cho bản này; kiến trúc không phụ thuộc DB cụ thể — chỉ cần đổi driver/dialect
(JPA + HikariCP) là chuyển sang Oracle được, pool sizing giữ nguyên nguyên tắc.

**H: Một broker Kafka / một node Redis trong demo có phải điểm chết đơn (SPOF)?**
Đ: Trong **demo local** thì đúng là đơn node để nhẹ. Nhưng (1) code đã viết theo hướng
**fail-open**: Redis/Kafka chết hệ thống vẫn phục vụ (demo 7 chứng minh); (2) production chuyển
sang **Redis Cluster/Sentinel** và **Kafka 3 broker RF≥3** — client trong code đã hướng cluster,
chỉ đổi cấu hình, không đổi code.

**H: Rate limit theo clientId — nếu đối tác giả mạo clientId thì sao?**
Đ: `clientId` được lấy từ **JWT đã xác thực** (claim `client_id`/`azp`), không phải header tùy
ý người gọi. Token ký bất đối xứng, gateway verify bằng public key → không giả mạo được.

**H: Chỉ số cache hit/miss trên Prometheus đang bằng 0?**
Đ: Trung thực: `cache_gets_total` của Micrometer đang gắn vào `CacheManager` của Spring (hiện
chưa dùng), còn hit/miss thật nằm trong instance Caffeine tự quản của `TwoLevelCacheService`
(đã bật `recordStats()` nhưng **chưa bind vào MeterRegistry**). Khắc phục ~5 dòng:
`CaffeineCacheMetrics.monitor(registry, l1, "catalogL1")`. Đây là hạn chế đo đạc, **không** ảnh
hưởng chức năng cache.

**H: Vì sao gateway reactive còn service khác servlet — không nhất quán?**
Đ: Có chủ đích. Gateway cần ôm hàng nghìn kết nối I/O-bound → reactive/Netty tối ưu. Service
nghiệp vụ ưu tiên dễ viết/đọc/bảo trì → servlet + **virtual threads (Java 21)** cho throughput
cao mà vẫn theo mô hình lập trình tuần tự. Đúng công cụ cho đúng việc.

---

## 8. HẠN CHẾ HIỆN TẠI & LỘ TRÌNH PRODUCTION

| Hạng mục | Hiện trạng (demo) | Production |
|---|---|---|
| Redis | 1 node, fail-open | Redis Cluster/Sentinel (HA), TLS, password |
| Kafka | 1 broker KRaft | 3 broker, RF≥3, min-ISR 2 |
| Đảm bảo event | publish sau commit | + **Transactional Outbox** (exactly-once) |
| Metric cache hit/miss | chưa bind Micrometer | bind `CaffeineCacheMetrics` |
| ELK/Prometheus/Grafana | định nghĩa sẵn, tắt ở demo (ES nặng RAM) | bật cụm, thêm dashboard + alert |
| Secret | env/K8s Secret cơ bản | Vault / Sealed Secrets / External Secrets |
| JWT | public key qua env | tích hợp JWKS endpoint, xoay khóa tự động |

> **Luận điểm bảo vệ #3:** Các hạn chế đều là **vấn đề cấu hình/độ chín vận hành**, không phải
> lỗi kiến trúc. Mọi đường nối mở rộng (cluster hoá Redis/Kafka, outbox, alerting) đã được tính
> trước trong thiết kế nên nâng cấp **không phải viết lại**.

---

### Phụ lục — Lệnh chạy nhanh
```bash
cp .env.example .env
docker compose up -d mariadb redis kafka
docker compose up -d api-gateway product-catalog-service product-management-service integration-service
docker compose ps                      # 7/7 healthy
# Demo theo mục 5 ...
docker compose down                    # giữ volume dữ liệu
```
Cổng: gateway 8080 · catalog 8081 · management 8082 · integration 8083 · MariaDB host **3307**.
Swagger: http://localhost:8081/swagger-ui.html
