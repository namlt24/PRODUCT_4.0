# KẾ HOẠCH HẠ TẦNG PRODUCTION — BCCS PRODUCT CATALOG 4.0

> Mục tiêu: hệ thống lớn, **hàng triệu–chục triệu request/ngày**, sẵn sàng cao, độ trễ đọc sub-ms,
> không SPOF ở bất kỳ tầng nào. Tài liệu gồm: giả định tải → kiến trúc tham chiếu → best practice
> theo tầng → **bảng sizing dự kiến** → HA/DR → bảo mật → observability → CI/CD → checklist.
>
> Mọi số sizing đều kèm **công thức** (Phụ lục A) để tính lại khi tải đổi — không phải số "thần thánh".

---

## 1. GIẢ ĐỊNH TẢI (Capacity baseline)

Chọn **điểm thiết kế: 50 triệu request/ngày** (bao trùm "hàng triệu", còn dư cho tăng trưởng).

| Chỉ số | Giá trị | Cách tính |
|---|---|---|
| Trung bình | **~580 req/s** | 50.000.000 / 86.400 |
| Hệ số đỉnh (peak/avg) | **×5** | telecom + quét batch của đối tác → không đều |
| **Đỉnh** | **~2.900 req/s** | 580 × 5 |
| Hệ số dự phòng thiết kế | **×1.7–2** | chịu lỗi + tăng trưởng |
| **Công suất thiết kế** | **~5.000 req/s bền, burst ~8–10k** | đỉnh × ~1.7 |
| Tỉ lệ Đọc/Ghi | **~97% / 3%** | read-heavy |
| Đỉnh đọc | **~2.800 req/s** | |
| Đỉnh ghi | **~90 req/s** | |
| Cache hit (L1+L2) | **95–98%** | giả định bảo thủ |
| **QPS chạm DB (đọc)** | **~85 q/s** | 2.800 × (1 − 0.97) |
| QPS ghi DB | ~90 q/s | |

> 🔑 **Insight cốt lõi để bảo vệ:** nhờ cache 2 tầng, DB chỉ nhận **~175 QPS** dù hệ thống phục vụ
> ~2.900 req/s. Cache là thứ "hấp thụ" tải; DB **không** cần khổng lồ. Nếu tải ×10 (500M/ngày),
> chỉ cần scale **gateway + catalog + Redis** theo chiều ngang, DB tăng nhẹ.

---

## 2. KIẾN TRÚC THAM CHIẾU PRODUCTION (phân tầng, đa AZ)

```
                          Đối tác / Hệ thống bên thứ 3
                                    │  (HTTPS, traceparent)
                  ┌─────────────────▼──────────────────┐
   TẦNG BIÊN      │  DNS (geo/health) → CDN(tùy) → WAF  │  chống DDoS, TLS, lọc
   (Edge)         │  → L4/L7 Load Balancer (Cloud LB / F5)│  (HA sẵn của nhà cung cấp)
                  └─────────────────┬──────────────────┘
                                    │
                  ┌─────────────────▼──────────────────┐
   TẦNG CỔNG      │   Ingress + API Gateway (N replica) │  routing, rate-limit, JWT,
                  │   (Spring Cloud GW, stateless)      │  circuit breaker, tracing
                  └───┬───────────┬───────────┬─────────┘
                      │           │           │
   TẦNG DỊCH VỤ   catalog(R)   management(W) integration   (K8s Deployment + HPA + PDB)
   (đa AZ)         3..30 pod    2..8 pod      2..6 pod
                      │           │
        ┌─────────────┼───────────┴──────────────┐
        ▼             ▼                           ▼
   Redis Cluster   Kafka (3 broker, RF3)     MariaDB HA
   (L2, 3M+3R)     catalog.changes           Primary + 2 Replica + MaxScale
        ▲                                         │  (semi-sync, PITR, backup)
        │  L1 Caffeine in-pod (mỗi catalog pod)   │
        └─────────────────────────────────────────┘
                                    │
   QUAN SÁT     Prometheus+Thanos · Grafana · ELK/OpenSearch · Alertmanager · Jaeger/Tempo
   NỀN TẢNG     K8s đa AZ · GitOps (ArgoCD) · Vault/Secret Manager · Image registry+scan
```

Nguyên tắc bất biến: **mọi tầng ≥2 instance, trải ≥3 AZ, health-check chủ động, không SPOF.**

---

## 3. BEST PRACTICE THEO TẦNG

### 3.1 Biên (Edge)
- **Cloud Managed LB** (ALB/NLB, GCLB) hoặc **F5** (nếu telco chuẩn hoá) — đã HA sẵn. Đừng tự dựng 1 LB đơn.
- **WAF + DDoS protection** ở biên; **TLS 1.3 termination**, HSTS; rate-limit thô ở WAF, rate-limit theo clientId ở gateway.
- **CDN** cho các GET cacheable (đặt `Cache-Control` — controller đã trả max-age) để chặn bớt traffic trước khi vào hệ thống.

### 3.2 API Gateway
- **Stateless, N replica** (state rate-limit ở Redis ngoài) → scale ngang thoải mái.
- `PodDisruptionBudget` (minAvailable) + `maxUnavailable:0` khi rollout + topology spread đa AZ.
- **JWT verify in-memory bằng JWKS** (xoay khóa tự động) thay vì public key tĩnh; introspection chỉ là fallback có circuit breaker.

### 3.3 Dịch vụ (catalog/management/integration)
- **HPA** theo CPU **và** RPS (`http_server_requests`); `readiness=db,ping` (fail-open Redis/Kafka).
- **Resource requests/limits** rõ ràng (tránh noisy-neighbour); **virtual threads (Java 21)** cho servlet.
- **Graceful shutdown** (drain) + `terminationGracePeriodSeconds` để không rớt request khi scale-in.

### 3.4 Cache (Redis)
- **Redis Cluster** (sharding) hoặc **Sentinel** (HA), bật **AOF + RDB**, `maxmemory-policy allkeys-lru`.
- Giữ **random TTL** (chống avalanche) + **null marker** (chống penetration) như đã code.
- Tách instance/ logical DB cho **rate-limiter** và **cache dữ liệu** để cô lập.

### 3.5 Messaging (Kafka)
- **≥3 broker, RF=3, min.insync.replicas=2**, `acks=all` (đã cấu hình) → không mất event.
- Partition topic `catalog.changes` ≥ số catalog pod tối đa (để scale consumer). **Transactional Outbox** cho exactly-once.
- Giám sát **consumer lag** (đã expose metric).

### 3.6 Database (MariaDB HA)
- **Primary + ≥2 Read-Replica + MaxScale** (read/write split + auto-failover) — khớp CQRS read-heavy. Hoặc **Galera 3 node**.
- **Semi-sync replication** (không mất giao dịch khi failover) + **binlog → PITR**.
- **DB proxy/connection pooling** (MaxScale/ProxySQL) để gom kết nối; HikariCP per-service vẫn giữ.
- **Backup hằng ngày + PITR** (đã có `scripts/db-backup/`). **HA ≠ Backup**: cần cả hai.

### 3.7 Bảo mật xuyên suốt
- **mTLS nội bộ** (service mesh Istio/Linkerd tùy chọn) hoặc NetworkPolicy chặt.
- **Secrets**: Vault / Cloud Secret Manager / Sealed Secrets — **không** secret trong YAML/Git.
- Image **scan CVE**, chạy **non-root**, read-only FS, distroless/JRE-alpine (đã làm).

---

## 4. BẢNG SIZING DỰ KIẾN (cho điểm thiết kế ~2.900 req/s đỉnh)

> Thông lượng tham chiếu mỗi instance là **bảo thủ**; đo tải thật (k6/Gatling) để tinh chỉnh.

### 4.1 Tầng ứng dụng (K8s pods)

| Thành phần | Throughput/ pod (bảo thủ) | Replica (min→max HPA) | requests / limits mỗi pod |
|---|---|---|---|
| **api-gateway** | ~2.500 req/s | **4 → 12** | 0.5 / 1.5 vCPU · 512Mi / 1Gi |
| **product-catalog** (đọc, cache hit) | ~1.500 req/s | **4 → 20** | 1 / 2 vCPU · 768Mi / 1.5Gi |
| **product-management** (ghi) | ~500 req/s | **2 → 6** | 0.3 / 1 vCPU · 512Mi / 1Gi |
| **integration** (JWT/introspect) | ~2.000 req/s | **2 → 6** | 0.25 / 1 vCPU · 384Mi / 768Mi |

- Catalog: đỉnh 2.800 read/s ÷ 1.500 ≈ 2 pod tải, +HA+headroom → **min 4**, HPA tới 20 khi ×7 tải.
- L1 Caffeine: 100k entry × ~1KB ≈ **~100MB/pod** → nằm gọn trong 1.5Gi.

### 4.2 Tầng dữ liệu/hạ tầng (thường VM/managed riêng, không chung node app)

| Thành phần | Số node | CPU / RAM mỗi node | Đĩa | Ghi chú sizing |
|---|---|---|---|---|
| **Redis Cluster** | 3 master + 3 replica | 2–4 vCPU / **8–16 GB** | — (in-mem) | **Sizing theo BỘ NHỚ**, không phải QPS. Dữ liệu cache = (#product+#offer) × ~1KB × 1.5 overhead. VD 5M bản ghi ≈ 5GB → chọn 8–16GB/node. |
| **Kafka** | **3 broker** | 2–4 vCPU / 8–16 GB | **200–500 GB SSD**/broker | Throughput event nhỏ (~90/s). Sizing theo **retention×RF**: 90/s×200B×7 ngày×RF3 ≈ ~30GB; chừa headroom. 12 partition cho topic. |
| **MariaDB Primary** | 1 (+failover) | **8–16 vCPU** / **32–64 GB** | NVMe, **≥10k IOPS**, vài trăm GB | RAM ≈ **buffer pool ôm working set** (giữ hot data trong RAM). Ghi ~90/s nhẹ. |
| **MariaDB Read-Replica** | **2–3** | 8–16 vCPU / 32–64 GB | NVMe ≥10k IOPS | Phục vụ ~85 read-miss/s (rất nhẹ) nhưng sizing để **scale đọc + buffer pool**. |
| **MaxScale/ProxySQL** | 2 (HA, VIP) | 2–4 vCPU / 4–8 GB | — | Gom kết nối + auto read/write split + failover. |

**Kết nối DB (chống starvation):** tổng HikariCP < `max_connections`.
VD: catalog 8 pod × 40 + management 4 pod × 20 = **400** → dùng **MaxScale gom** về primary, đặt `max_connections` ≈ 1.000 (chừa hệ thống). Đây là lý do nên có DB proxy ở quy mô lớn.

### 4.3 Observability (tốn kém nhất là log)

| Thành phần | Sizing | Ghi chú |
|---|---|---|
| **Prometheus + Thanos/Mimir** | 2–4 vCPU / 8–16 GB + object storage | Local 15–30 ngày, dài hạn đẩy object storage. |
| **Elasticsearch/OpenSearch** | **3+ data node**, 8 vCPU / 32–64 GB, **TB SSD** | ⚠️ **Hog lưu trữ**: ~3.000 req/s × vài log/req × ~1KB ≈ **vài trăm GB–~1TB/ngày** raw. Giảm bằng level INFO, **sampling**, retention 7–14 ngày hot + archive object storage. |
| **Grafana / Alertmanager / Jaeger(Tempo)** | 1–2 vCPU / 2–4 GB mỗi cái | Tracing nên **sampling** (vd 5–10%) ở production. |

### 4.4 Kích thước cụm Kubernetes (gợi ý)

| Node pool | Số node | Spec mỗi node | Dùng cho |
|---|---|---|---|
| **app** | **6–10** (đa 3 AZ) | 8 vCPU / 32 GB | gateway + 3 service + ingress |
| **observability** | 3–4 | 8 vCPU / 32–64 GB | Prometheus/ELK |
| **stateful** (hoặc managed) | 6–9 | theo bảng 4.2 | Redis/Kafka/DB (ưu tiên **managed service** nếu cloud) |

> Cloud: ưu tiên **managed** (RDS/ElastiCache/MSK hoặc Aurora/Memorystore/Confluent) để giảm vận hành; on-prem thì tự dựng theo bảng trên.

---

## 5. HA / DR (RPO / RTO)

| Mức | Cơ chế | RPO | RTO |
|---|---|---|---|
| **Pod/node chết** | K8s reschedule + replica + readiness gỡ Endpoint | 0 | vài giây |
| **AZ chết** | trải ≥3 AZ, LB đa AZ, DB replica khác AZ | ~0 (semi-sync) | giây–phút (failover tự động) |
| **Region chết (DR)** | region passive: replica DB cross-region (async), Kafka MirrorMaker, DNS failover | phút (async) | phút–giờ |
| **Dữ liệu hỏng/xóa nhầm** | **backup hằng ngày + binlog PITR** | ≤ 24h (về điểm bất kỳ với PITR) | giờ |

- **Quy tắc 3-2-1 backup**, kiểm thử **restore định kỳ** (backup chưa test = không có backup).
- Chạy **GameDay / chaos test** định kỳ theo [KICH-BAN-TEST-RESILIENCE.md](KICH-BAN-TEST-RESILIENCE.md).

---

## 6. CI/CD & ROLLOUT

- **GitOps (ArgoCD/Flux)**: Git là nguồn sự thật; mọi thay đổi qua PR + review.
- Pipeline: build (container Java 21) → test → **scan CVE/secret** → ký image → push registry → deploy.
- **Rollout an toàn**: canary (5%→25%→100%) hoặc blue-green; `maxUnavailable:0`; tự rollback khi alert.
- Môi trường: **dev → staging (giống prod thu nhỏ) → prod**; load test ở staging trước khi lên.

---

## 7. CHECKLIST TRƯỚC KHI LÊN PRODUCTION

- [ ] Mọi tầng ≥2 instance, trải ≥3 AZ, có PDB; không còn SPOF.
- [ ] DB: Primary+Replica+MaxScale, semi-sync, **PITR + backup đã test restore**.
- [ ] Redis Cluster/Sentinel + AOF; Kafka 3 broker RF3 minISR2.
- [ ] Secrets ở Vault/Secret Manager; mTLS/NetworkPolicy; image non-root + scan.
- [ ] HPA + resource limits + graceful shutdown; **load test đạt công suất thiết kế** (k6/Gatling tới ~8–10k req/s burst).
- [ ] Dashboard + **alert** (p99 latency, error rate, cache hit, **Kafka lag**, HikariCP pool, DB replication lag).
- [ ] Runbook sự cố + GameDay chaos định kỳ.
- [ ] Capacity review hằng quý (so với tăng trưởng thực).

---

## PHỤ LỤC A — CÔNG THỨC SIZING (tự tính khi tải đổi)

```
avg_rps   = requests_per_day / 86.400
peak_rps  = avg_rps × peak_factor           (telecom: 4–5)
design_rps= peak_rps × headroom             (1.7–2)

read_peak = peak_rps × read_ratio           (0.97)
write_peak= peak_rps × write_ratio          (0.03)

db_read_qps = read_peak × (1 − cache_hit)   (hit 0.95–0.98)
pods(svc)   = ceil(peak_for_svc / throughput_per_pod) × HA_factor   (HA ≥1.5–2)

redis_mem   = (num_cached_keys × avg_value_bytes) × 1.5_overhead
kafka_disk  = events_per_s × avg_event_bytes × retention_s × RF
db_buffer_pool ≈ working_set_size (giữ hot data trong RAM); 50–75% RAM node
hikari_total= Σ(pool_size × pods) < db_max_connections   (dùng DB proxy nếu lớn)
es_storage_per_day = log_events_per_s × avg_log_bytes × 86.400 × replicas
```

## PHỤ LỤC B — NẾU TẢI ×10 (≈500 triệu req/ngày, ~29k req/s đỉnh)
- Scale **ngang**: gateway ~20–40 pod, catalog ~30–80 pod (HPA), Redis Cluster nhiều shard hơn (RAM theo dataset).
- DB vẫn nhẹ nhờ cache (~850 read-miss/s + ghi) → thêm **read-replica** + tăng buffer pool; cân nhắc **sharding** nếu dataset/ghi tăng mạnh.
- Kafka: tăng partition + có thể thêm broker; **bật multi-region** cho DR.
- Điểm nghẽn thật sẽ là **chi phí log (ELK)** và **băng thông LB/TLS** — tối ưu trước.
