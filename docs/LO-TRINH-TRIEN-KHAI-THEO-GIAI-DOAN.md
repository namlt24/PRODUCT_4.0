# Lộ trình triển khai theo giai đoạn — BCCS Product Catalog 4.0

> Tài liệu định hướng: từ hạ tầng demo hiện tại → production phục vụ hàng triệu request/ngày.
> Nguyên tắc xuyên suốt: **code chạy trên hạ tầng tạm vẫn dùng lại được khi lên production** —
> phần lớn việc còn lại là *hạ tầng ngoài + cấu hình*, không phải viết lại code (12-factor).

---

## 0. Đánh giá hạ tầng hiện tại

| Hạng mục | Hiện trạng | Đủ cho | Thiếu cho production |
|---|---|---|---|
| App (4 service + rate limit động) | ✅ chạy được | Demo/dev | — |
| Infra data (MariaDB/Redis/Kafka) | ⚠️ single-node | Demo | HA: replica/cluster |
| K8s | ✅ kind (1 node) | Test logic | Cluster thật ≥3 worker, multi-AZ |
| Observability | ✅ Prom/Grafana/ELK/APM | Tốt | Alerting + retention/ILM + HA |
| Secrets | ⚠️ `.env` demo creds | Demo | Vault / Sealed-Secrets |
| CI/CD | ❌ chưa có | — | Bắt buộc |
| Load test / sizing | ❌ chưa làm | — | Bắt buộc trước go-live |
| Backup / DR | ⚠️ có script, chưa test restore | — | Test restore định kỳ |

**Kết luận:** hạ tầng hiện tại đủ tốt để **code + test chức năng**. Còn 6 khoảng trống lớn cho
production: HA tầng data, secrets management, CI/CD, load test/sizing, alerting, DR đã kiểm chứng.

---

## 1. Sơ đồ giai đoạn

```
GĐ0  Hạ tầng đơn giản           ✅ XONG   (docker-compose + kind, chỉ cần chạy được luồng)
GĐ1  Build & test trên k8s      ✅ gần xong (manifests + observability)
GĐ2  Code phần mềm              ⬅ ĐANG Ở ĐÂY
GĐ2.5 CI/CD                      ⏩ chèn TRƯỚC khi deploy prod (không để cuối)
GĐ3  Deploy production          (bổ sung HA, secrets, ingress/TLS, alerting, DR)
GĐ4  Load test & sizing          ⏩ TRƯỚC go-live (không phải sau)
```

**Hai điều chỉnh quan trọng so với kế hoạch ban đầu:**
1. **Đẩy CI/CD lên trước GĐ deploy** — mọi deploy prod đi qua pipeline để có audit + rollback.
2. **Đặt load-test/sizing TRƯỚC go-live** — sizing sai thì hoặc đốt tiền, hoặc sập khi tải thật.

---

## 2. Chi tiết từng giai đoạn

### GĐ0 — Hạ tầng đơn giản ✅ XONG
docker-compose single-node + kind. Mục tiêu duy nhất: chạy được luồng end-to-end.
**Không tối ưu/HA hóa gì ở đây** — đừng phí công lên cái sẽ vứt đi.

---

### GĐ1 — Build & test trên k8s ✅ gần xong
Checklist bổ sung (rẻ, viết một lần dùng lại mãi):
- [ ] `readiness / liveness / startup probe` đầy đủ cho **cả 4 service** (rà lại gateway).
- [ ] `resources.requests/limits` cho **mọi pod** — thiếu thì HPA và scheduler hoạt động sai.
- [ ] `PodDisruptionBudget` + `topologySpreadConstraints` (viết sẵn, prod mới có tác dụng).
- [ ] ConfigMap/Secret tách bạch non-secret vs secret (đang làm đúng — giữ vậy).

---

### GĐ2 — Code phần mềm ⬅ ĐANG Ở ĐÂY
Làm trên hạ tầng tạm là đúng. Lưu ý để **không phải sửa lại khi lên prod**:
- [ ] **12-factor**: mọi cấu hình qua env/ConfigMap/Secret (đang đúng).
- [ ] **Không hardcode** DNS nội bộ (`mariadb`, `redis`) trong code — đã tách qua env.
- [ ] Code phải **chịu được infra HA sau này**:
  - Redis Cluster → key rate-limit đã có hash-tag `{...}` ✅
  - Kafka nhiều partition → consumer **idempotent** (cache eviction vốn idempotent) ✅
- [ ] **Migration schema bằng Flyway/Liquibase** thay cho `schema.sql` mount-init
  (xem cảnh báo #1) — nên đưa vào ngay GĐ này.

> Phần lớn HA hóa data tier là **hạ tầng ngoài, không phải sửa code** — cứ code thoải mái,
> lên prod đổi connection string + cấu hình là chạy.

---

### GĐ2.5 — CI/CD (chèn TRƯỚC deploy prod)
- [ ] **CI**: build trong container Java 21 → `mvn test` → build image → push registry →
  scan ảnh (Trivy).
- [ ] **CD**: GitOps (ArgoCD/Flux) hoặc `kubectl apply` qua pipeline.
- [ ] **Tag ảnh theo git SHA**, KHÔNG dùng `:latest` (để rollback xác định được).
- [ ] Audit + rollback tự động; tránh thao tác tay lên cluster.

---

### GĐ3 — Deploy production trên k8s
Đa số là **hạ tầng ngoài, ít đụng code**:
- [ ] **HA data tier**:
  - MariaDB: Galera / primary-replica / managed (HA ≠ backup).
  - Redis: Sentinel hoặc Cluster.
  - Kafka: ≥3 broker, replication-factor = 3, min-ISR = 2.
- [ ] **Secrets thật**: Vault / Sealed-Secrets thay `.env`; **xoay (rotate) toàn bộ creds demo**.
- [ ] **Ingress + TLS** (cert-manager) + LB/WAF trước gateway (LB mềm vs cứng — xem doc HA).
- [ ] **Alerting** (Alertmanager): p99 latency, tỉ lệ 5xx, spike 429, pod restart,
  Kafka consumer lag, độ trễ replica DB, đầy disk ES.
- [ ] **DR**: chạy thử **restore** từ backup định kỳ (backup chưa test = chưa có backup).
- [ ] ES cluster + retention/ILM (single-node ES sẽ đầy đĩa).

---

### GĐ4 — Load test & sizing (TRƯỚC go-live)
- [ ] Công cụ: **k6 / Gatling / JMeter**, bắn **qua gateway**.
- [ ] Đo: throughput tối đa/pod, p50/p95/p99, ngưỡng HPA, điểm bão hòa DB pool (read 40 + write 20),
  cache hit ratio, hiệu quả rate-limit (429 đúng ngưỡng đối tác).
- [ ] Output: **con số sizing thật** thay cho design point 50M req/ngày → chỉnh
  `requests/limits`, số replica, pool size, TTL cache theo số đo thực.

---

## 3. Cảnh báo / điểm dễ vấp

1. **`ddl-auto: validate` + `schema.sql` mount-init** chỉ chạy lần đầu khi volume trống.
   Prod cần **Flyway/Liquibase**, nếu không mỗi lần đổi schema (như 3 bảng rate-limit vừa thêm)
   sẽ lệch giữa các môi trường.
2. **Gateway giờ phụ thuộc DB** (nạp rate-limit). Đã fail-safe (`initialization-fail-timeout: -1`).
   → Test kịch bản DB chết lúc gateway khởi động: gateway vẫn phải lên và dùng default limit.
3. **Rate-limit refresh 15s in-memory**: nhiều replica gateway refresh lệch pha tới 15s →
   trong cửa sổ đó các replica có thể áp hạn mức khác nhau. Chấp nhận được; nếu cần đổi tức thời
   (treo đối tác gấp) → Admin API + pub/sub (xem `DYNAMIC-RATE-LIMITING.md`, mục production).
4. **Observability single-node ES**: prod cần ES cluster + retention/ILM.
5. **kind ≠ k8s thật**: networking, storage class, LoadBalancer khác hẳn. "Chạy trên kind"
   chỉ chứng minh logic đúng, **không** chứng minh chạy được trên prod.

---

## 4. Tóm tắt 1 dòng

Giữ nguyên 4 giai đoạn, **đẩy CI/CD lên trước deploy** và **đặt load-test/sizing trước go-live**.
Phần còn lại chủ yếu là hạ tầng ngoài + cấu hình — **cứ code tiếp trên hạ tầng tạm hiện tại**.
