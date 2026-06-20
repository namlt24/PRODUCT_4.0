# DEMO/DEV vs PRODUCTION — CẦN LÀM GÌ TRƯỚC, NÂNG CẤP Ở ĐÂU

> Trả lời câu hỏi: *Để demo/báo cáo thì đã cần làm các phần "go-live checklist" còn thiếu chưa?
> Hay phát triển trên hạ tầng tạm rồi tới production làm tiếp? Cần nâng cấp gì TRONG ứng dụng
> (đổi cấu hình/code) hay nâng cấp hạ tầng NGOÀI?*
>
> Đọc kèm: [PRODUCTION-GO-LIVE-CHECKLIST.md](PRODUCTION-GO-LIVE-CHECKLIST.md).

---

## 1. Cho DEMO / BÁO CÁO → KHÔNG cần làm gì thêm
Hạ tầng hiện tại (docker-compose + K8s kind + stack giám sát) **đủ để demo, báo cáo và phát triển
ứng dụng**. Checklist go-live là cho **production thật** — làm bây giờ sẽ phí công và làm chậm phát triển.

## 2. Nguyên tắc cốt lõi: ứng dụng đã "12-factor"
App externalize cấu hình qua **biến môi trường** (DB host/port, Redis, Kafka, JWT key…). Nhờ vậy
**phần lớn nâng cấp HA/bảo mật là "hạ tầng NGOÀI + đổi cấu hình deploy", KHÔNG sửa code** → để dành
tới production được.

Ngoại lệ: **một nhóm nhỏ là logic/hợp đồng API trong app** — retrofit về sau rất đắt & rủi ro →
nên làm/thiết kế **sớm** ngay trong giai đoạn dev.

---

## 3. Phân loại 3 nhóm

### 🟦 NHÓM A — Hạ tầng NGOÀI (không đụng code → để dành production hoàn toàn)
Chỉ đổi cấu hình deploy + dựng hạ tầng khi lên prod:
- TLS biên / WAF / DDoS / LB HA / NetworkPolicy / K8s RBAC / cert-manager / NTP
- **mTLS qua service mesh** (sidecar Istio/Linkerd — KHÔNG sửa code)
- **Mã hoá at-rest** (DB/Redis/ES) · **Secrets manager** (Vault/Sealed Secrets/External Secrets)
- **Redis Cluster/Sentinel · Kafka 3-broker RF3 · DB Primary+Replica+MaxScale**
  → app chỉ đổi *connection string / broker list* qua env, không sửa code
- Backup/PITR · đưa Monitoring lên prod · CI/CD GitOps · image scan/tag bất biến · Multi-AZ/DR · Autoscaler

→ **Toàn bộ nhóm này để production làm tiếp, không ảnh hưởng việc dev hiện tại.**

### 🟨 NHÓM B — Trong ỨNG DỤNG (code/config) — nên làm SỚM
Retrofit sau khi đã có dữ liệu thật + nhiều môi trường + đối tác đã tích hợp thì rất đắt:

| Hạng mục | Vì sao nên sớm | Chi phí giờ |
|---|---|---|
| **Flyway/Liquibase migration** (thay `ddl-auto=validate` + schema.sql thủ công) | Đã có data nhiều env thì đổi schema thủ công sẽ vỡ; migration cần lịch sử từ đầu | Thấp |
| **Idempotency key cho ghi** + **API versioning** | Là **hợp đồng API** — đối tác build theo; thêm sau = breaking change | Thấp (đã có `/api/v1`) |
| **Graceful shutdown** | 1 dòng config Spring Boot; tránh rớt request khi rollout | Rất thấp |
| **Audit log đổi giá/danh mục** | Yêu cầu nghiệp vụ viễn thông; nhồi sau khó truy vết quá khứ | Trung bình |
| **Bind Caffeine vào Micrometer** | Metric cache hit/miss đúng (hiện = 0) | Rất thấp |

→ **Đề xuất làm trong giai đoạn dev:** **Flyway · Idempotency (vào API v1) · Graceful shutdown**
(rẻ nhất + ảnh hưởng hợp đồng API). Còn lại để prod.

### 🟧 NHÓM C — Vừa code vừa hạ tầng → để dành nhưng "thiết kế trước"
Làm ở production được, nhưng nên chừa chỗ trong thiết kế kẻo sau phải sửa nhiều:
- **Transactional Outbox** (exactly-once Kafka) — thêm bảng outbox + relay; ảnh hưởng luồng ghi
- **JWT → JWKS** (xoay khoá) — sửa auth filter; hiện đã có chỗ cắm public key
- **Cache stampede / warming / versioned key** — tinh chỉnh logic cache
- **Schema Registry** cho event Kafka

---

## 4. Tóm tắt 1 câu
> **Demo/báo cáo: không cần làm gì thêm.** Khi dev tiếp trên hạ tầng tạm: phần lớn (HA, bảo mật,
> mã hoá, cluster Redis/Kafka/DB, monitoring, CI/CD) là **hạ tầng NGOÀI + đổi env khi lên prod —
> không sửa code**. Chỉ nên làm sớm **vài thứ thuộc app/hợp đồng API** (Flyway, idempotency/versioning,
> graceful shutdown, audit) vì retrofit về sau rất đắt.

## 5. Ánh xạ nhanh: "đổi ở đâu?"
| Nâng cấp | TRONG app (code/config) | NGOÀI app (hạ tầng/deploy) |
|---|---|---|
| Redis Cluster / Kafka 3-broker / DB HA | chỉ env (host/broker) | ✅ dựng cluster |
| TLS / mTLS / WAF / mã hoá at-rest | — | ✅ |
| Secrets manager | chỉ cách inject (env/secret) | ✅ dựng Vault |
| Monitoring / CI-CD / Autoscaler | annotation/healthcheck (đã có) | ✅ |
| Flyway migration | ✅ code/dependency | — |
| Idempotency / API versioning | ✅ code (hợp đồng API) | — |
| Graceful shutdown | ✅ 1 dòng config | — |
| Transactional Outbox / JWKS | ✅ code | một phần (Kafka/JWKS endpoint) |
