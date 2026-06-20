# PRODUCTION GO-LIVE CHECKLIST — BCCS PRODUCT CATALOG 4.0

> Danh sách kiểm tra trước khi đưa hệ thống lên production thật (hệ thống mở cho đối tác bên thứ 3,
> hàng triệu request/ngày). Tick `[x]` khi hoàn thành.
>
> Ký hiệu mức ưu tiên: **P0** = chặn go-live · **P1** = nên có trước go-live · **P2** = sau go-live.
> Trạng thái dự án hiện tại: ✅ đã có · 🟡 một phần · ⛔ chưa có.

---

## A. BẢO MẬT (P0 — rủi ro cao nhất vì mở cho đối tác)

- [ ] **P0** ⛔ Đưa secret ra khỏi Git/YAML → **Vault / Sealed Secrets / External Secrets**; **xoay** mọi mật khẩu đã từng lộ (`.env`, k8s Secret demo).
- [ ] **P0** ⛔ **TLS 1.3** ở biên (đối tác→gateway), HSTS; **mã hoá in-transit nội bộ** (mTLS/service mesh).
- [ ] **P0** ⛔ **Mã hoá at-rest**: MariaDB (TDE/volume), Redis, Elasticsearch.
- [ ] **P0** 🟡 **JWT**: dùng **JWKS + xoay khoá tự động**; kiểm `iss`/`aud`/`exp`, clock skew; cơ chế thu hồi token.
- [ ] **P0** ⛔ **NetworkPolicy** zero-trust; backend **không** có IP public/Ingress ra ngoài.
- [ ] **P1** 🟡 **WAF + chống DDoS** ở biên; **rate-limit + quota theo từng đối tác** (hiện có rate-limit theo clientId).
- [ ] **P1** ⛔ **Image security**: scan CVE, ký image (cosign), read-only FS, SBOM (đã chạy non-root).
- [ ] **P1** ⛔ **Audit log** thay đổi giá/danh mục (ai – gì – khi nào).
- [ ] **P1** ⛔ K8s **RBAC least-privilege**; bỏ quyền cluster-admin thừa (vd token Dashboard test).
- [ ] **P1** ⛔ **Pen-test + security review** độc lập trước go-live.

## B. CƠ SỞ DỮ LIỆU

- [ ] **P0** ⛔ **Migration có version (Flyway/Liquibase)** thay `ddl-auto=validate` + schema.sql thủ công.
- [ ] **P0** ⛔ **Migration zero-downtime** (expand/contract) — phối hợp với rollout app.
- [ ] **P0** 🟡 **DB HA**: Primary + Read-Replica + MaxScale (hoặc Galera) — bỏ SPOF.
- [ ] **P0** 🟡 **Backup hằng ngày + PITR (binlog)**; **đã test restore** thực tế (script có sẵn `scripts/db-backup/`).
- [ ] **P1** ⛔ **DB proxy/connection pooling** (MaxScale/ProxySQL); tổng HikariCP < `max_connections` mỗi node.
- [ ] **P1** ⛔ **Semi-sync replication** (không mất giao dịch khi failover).
- [ ] **P2** ⛔ Kế hoạch tăng trưởng dữ liệu, index bloat, archiving dữ liệu cũ.

## C. CACHE (Redis + Caffeine)

- [ ] **P0** 🟡 **Redis Cluster/Sentinel** (HA) + AOF; tách logical DB rate-limiter vs cache dữ liệu.
- [ ] **P1** ⛔ **Cache warming / rollout từ từ** để tránh DB bị "đập" khi cache rỗng sau deploy.
- [ ] **P1** ⛔ **Chống cache stampede hot-key** (single-flight/mutex) — jitter mới chỉ chống avalanche diện rộng.
- [ ] **P1** 🟡 **Bind Caffeine L1 vào Micrometer** để có metric hit/miss thật (hiện `cache_gets_total`=0).
- [ ] **P2** ⛔ Versioned cache key để khử race evict-vs-reload (đã quan sát).

## D. KAFKA / SỰ KIỆN

- [ ] **P0** 🟡 **Kafka 3 broker, RF=3, min.insync.replicas=2** (đã `acks=all`+idempotence).
- [ ] **P0** ⛔ **Transactional Outbox** → exactly-once (hiện publish-after-commit có thể mất event nếu Kafka chết lâu).
- [ ] **P1** ⛔ **Schema Registry** + chính sách tương thích cho `CatalogChangeEvent`.
- [ ] **P1** ⛔ **Dead-letter topic** cho poison message; **alert consumer lag**.

## E. API & TÍCH HỢP ĐỐI TÁC

- [ ] **P0** 🟡 **Chính sách versioning API** (`/v1`, deprecation, backward-compat).
- [ ] **P0** ⛔ **Idempotency key** cho request ghi.
- [ ] **P1** ⛔ **Contract testing** (consumer-driven) với đối tác.
- [ ] **P1** ⛔ **Quota/throttling theo gói đối tác** + **API key lifecycle** (onboard/offboard/rotate).
- [ ] **P1** ⛔ **Cổng tài liệu/SDK** cho đối tác (OpenAPI đã có — publish thành portal).
- [ ] **P1** ⛔ **SLA** rõ ràng + đo lường + cơ chế thông báo vi phạm.

## F. RESILIENCE / HA / DR

- [ ] **P0** ✅ Fail-open Redis/Kafka, readiness `db,ping` (đã có).
- [ ] **P0** 🟡 **Gateway N replica + LB HA** (đã demo); active health-check (HAProxy/cloud LB) để bỏ penalty failover.
- [ ] **P0** ⛔ **Đa AZ** (≥3) + PodDisruptionBudget + topology spread.
- [ ] **P1** ⛔ **Multi-region DR** + DNS failover; xác định **RPO/RTO** mục tiêu.
- [ ] **P1** ⛔ **Timeout/retry budget end-to-end** (chống retry storm) + load shedding/backpressure.
- [ ] **P1** ⛔ **Graceful shutdown / connection draining** + `terminationGracePeriodSeconds`.
- [ ] **P2** ⛔ **GameDay / chaos test** định kỳ (kịch bản có sẵn `docs/KICH-BAN-TEST-RESILIENCE.md`).

## G. HIỆU NĂNG / MỞ RỘNG

- [ ] **P0** ⛔ **Load test** (k6/Gatling/JMeter) đạt công suất thiết kế **trước khi mở cho đối tác**.
- [ ] **P1** ⛔ **HPA** trên metric đúng (CPU + RPS) + **Cluster Autoscaler**.
- [ ] **P1** ⛔ **JVM tuning** + kiểm **virtual-thread pinning** (khối `synchronized`/JDBC ghim carrier thread).
- [ ] **P1** ⛔ Validate **HikariCP sizing** dưới tải thật; soát N+1 query, index.
- [ ] **P2** ⛔ Tối ưu cold start/JIT warmup (readiness gating).

## H. GIÁM SÁT / VẬN HÀNH

- [ ] **P0** 🟡 Stack metrics/log/trace (đã dựng trên K8s test) — đưa lên prod với **PVC/ILM/retention**.
- [ ] **P0** ⛔ **Định nghĩa SLO/SLI + error budget + ALERT** (p99 latency, error rate, cache hit, Kafka lag, HikariCP, DB replication lag).
- [ ] **P1** ⛔ **Runbook + on-call + quy trình sự cố + postmortem**; **synthetic monitoring** từ ngoài.
- [ ] **P1** ⛔ **Sampling tracing** (chi phí) + **scrub PII** trong log; quản lý chi phí/retention ELK.
- [ ] **P1** 🟡 **APM qua Kibana Fleet integration** (mapping/UI chuẩn) — hiện standalone field `text`.

## I. TRIỂN KHAI / PHÁT HÀNH

- [ ] **P0** ⛔ **CI/CD + GitOps (ArgoCD/Flux)**; môi trường **dev→staging→prod** đồng nhất.
- [ ] **P0** ⛔ **Tag image bất biến** (`4.0.0`/digest) — **bỏ `:latest`** ở prod; registry riêng + scan.
- [ ] **P1** ⛔ **Canary/blue-green** + phối hợp DB migration + rollback + **feature flags**.
- [ ] **P1** ⛔ Externalize config (12-factor) — không build lại theo môi trường.

## J. NỀN TẢNG K8S & HẠ TẦNG

- [ ] **P0** ⛔ **Managed K8s** (EKS/GKE/AKS) hoặc quy trình nâng cấp version rõ ràng.
- [ ] **P0** ⛔ **cert-manager** (xoay + cảnh báo hết hạn chứng chỉ) + **NTP/đồng bộ giờ**.
- [ ] **P1** ⛔ ResourceQuota/LimitRange/PriorityClass; StorageClass cho stateful (hoặc dùng managed DB/Redis/Kafka).
- [ ] **P1** ⛔ DNS, (tùy chọn) service mesh; tách node pool app vs stateful vs observability.

## K. TUÂN THỦ / CHI PHÍ (viễn thông)

- [ ] **P0** ⛔ **Data residency / chủ quyền dữ liệu**, quy định ngành, retention dữ liệu/log.
- [ ] **P1** ⛔ **Change management/duyệt** thay đổi giá/danh mục (gắn audit log).
- [ ] **P1** ⛔ **Quản lý chi phí** (ES/log tốn nhất), right-size, reserved/spot, egress.

---

## 🔴 TOP 8 LÀM TRƯỚC (chặn go-live)
1. Secrets ra khỏi Git (Vault/Sealed Secrets) + xoay mật khẩu đã lộ.
2. TLS biên + mTLS nội bộ + mã hoá at-rest.
3. DB migration có version (Flyway) + PITR/backup test restore.
4. DB HA + Kafka 3-broker + Redis Cluster (bỏ SPOF) + Transactional Outbox.
5. API versioning + quota/API-key theo đối tác + idempotency.
6. Load test đạt công suất thiết kế trước khi mở cho đối tác.
7. SLO/alert + runbook + on-call.
8. CI/CD GitOps + canary + tag image bất biến.

---

### Trạng thái đã làm trong dự án (tham chiếu)
- ✅ Cache 2 tầng + fail-open + readiness `db,ping`; ✅ rate-limit theo clientId; ✅ circuit breaker;
  ✅ tracing W3C + log JSON; ✅ backup/restore script; ✅ K8s manifests (prod + test) + stack giám sát;
  ✅ kịch bản resilience + kế hoạch HA/sizing.
- Chi tiết: [TAI-LIEU-BAO-VE-KIEN-TRUC.md](TAI-LIEU-BAO-VE-KIEN-TRUC.md), [KE-HOACH-HA-TANG-PRODUCTION.md](KE-HOACH-HA-TANG-PRODUCTION.md),
  [KICH-BAN-TEST-RESILIENCE.md](KICH-BAN-TEST-RESILIENCE.md), [TRIEN-KHAI-K8S.md](TRIEN-KHAI-K8S.md),
  [TRIEN-KHAI-GIAM-SAT-K8S.md](TRIEN-KHAI-GIAM-SAT-K8S.md).
