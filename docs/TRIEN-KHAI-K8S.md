# HƯỚNG DẪN TRIỂN KHAI KUBERNETES (cụm test local — kind)

> Tài liệu mô tả cách dựng cụm K8s test trên máy local bằng **kind** (Kubernetes-in-Docker) và
> triển khai đầy đủ **infra (MariaDB/Redis/Kafka) + 4 microservice**. Đã chạy & smoke test thành
> công (CRUD + cache 2 tầng + Kafka invalidation). Manifest nằm ở **`k8s/test/`**.
>
> Cụm production thật dùng manifest ở `<service>/k8s/` (HPA, PVC, nhiều replica) — xem mục 7.

---

## 1. YÊU CẦU

| Công cụ | Phiên bản đã dùng | Vai trò |
|---|---|---|
| Docker Desktop | 28.x (12 CPU / 8 GB) | chạy node kind |
| kind | v0.32.0 | tạo cụm K8s trong Docker |
| kubectl | v1.32.2 | điều khiển cụm |
| (đã build) | image 4 service `bccs-product-catalog-*:latest` | qua `docker compose build` |

> RAM 8GB là tối thiểu cho cụm test này (replica=1). Cần dừng các stack khác (docker-compose) để nhường RAM.

---

## 2. CẤU TRÚC MANIFEST (`k8s/test/`)

| File | Nội dung |
|---|---|
| `00-namespace-config.yaml` | Namespace `bccs`, ConfigMap `bccs-config` (service discovery), Secret `bccs-secrets` (DB/JWT), ConfigMap `mariadb-initdb` (schema.sql) |
| `10-infra.yaml` | Deployment+Service: **redis**, **mariadb** (mount schema init), **kafka** (KRaft single-node) |
| `20-services.yaml` | Deployment+Service: **integration**, **product-catalog**, **product-management**, **api-gateway** (NodePort 30080). Có `initContainers` chờ phụ thuộc + readiness/liveness probe |

**Service discovery:** các service gọi nhau qua **tên Service trong cùng namespace** (`mariadb:3306`,
`redis:6379`, `kafka:9092`, `product-catalog-service:8081`…). Cấu hình bơm qua `envFrom` (ConfigMap + Secret).

---

## 3. CÁC BƯỚC TRIỂN KHAI (từ đầu)

```bash
# (0) Build image 4 service (nếu chưa có) — build trong container Java 21
docker compose build api-gateway product-catalog-service product-management-service integration-service

# (1) Tạo cụm kind (map NodePort 30080 -> host 18080 cho gateway)
cat > kind-bccs.yaml <<'EOF'
kind: Cluster
apiVersion: kind.x-k8s.io/v1alpha4
name: bccs-test
nodes:
  - role: control-plane
    extraPortMappings:
      - containerPort: 30080
        hostPort: 18080
        protocol: TCP
EOF
kind create cluster --config kind-bccs.yaml

# (2) Nạp image local vào node kind (BẮT BUỘC — kind không thấy image trên host)
for s in api-gateway product-catalog-service product-management-service integration-service; do
  kind load docker-image "bccs-product-catalog-$s:latest" --name bccs-test
done

# (3) Apply manifest theo thứ tự
kubectl config use-context kind-bccs-test
kubectl apply -f k8s/test/00-namespace-config.yaml
kubectl apply -f k8s/test/10-infra.yaml
kubectl apply -f k8s/test/20-services.yaml

# (4) Theo dõi đến khi 7/7 Ready
kubectl -n bccs get pods -w
```

Thứ tự khởi động tự xử lý nhờ **`initContainers`**: catalog/management/gateway chờ
`mariadb/redis/kafka` mở cổng rồi mới start container chính → không CrashLoop vì thiếu phụ thuộc.

---

## 4. TRUY CẬP & SMOKE TEST

**Gateway** đã expose sẵn qua NodePort → host `18080`:
```bash
curl http://localhost:18080/actuator/health          # -> 200
```

**Catalog/Management** là ClusterIP → dùng `port-forward` (port lạ tránh đụng app khác):
```bash
kubectl -n bccs port-forward svc/product-management-service 18082:8082 &
kubectl -n bccs port-forward svc/product-catalog-service   18081:8081 &

# Tạo sản phẩm (ghi DB + bắn Kafka event)
curl -X POST http://localhost:18082/api/v1/management/products -H "Content-Type: application/json" \
  -d '{"code":"K8S-1","name":"demo","category":"FTTH","status":"ACTIVE"}'

# Đọc qua cache (lần 1 DB ~1s, lần 2 cache ~ms)
curl http://localhost:18081/api/v1/products/<id>
```

**Kết quả smoke test thực tế (2026-06-20):**
```
gateway /actuator/health -> 200
create -> 201 (id trả về)
GET lần 1 (DB)    -> 200 (1.08s)
GET lần 2 (cache) -> 200 (0.009s)
UPDATE -> 200 ; đọc lại -> "name":"DA SUA tren K8s"   (Kafka invalidation OK)
```

---

## 5. VẬN HÀNH

```bash
kubectl -n bccs get pods,svc                       # trạng thái
kubectl -n bccs logs deploy/product-catalog-service -f   # log JSON
kubectl -n bccs scale deploy/product-catalog-service --replicas=2   # scale tay
kubectl -n bccs rollout restart deploy/api-gateway # rollout lại
kubectl -n bccs rollout status deploy/api-gateway  # theo dõi rollout
kubectl -n bccs describe pod <pod>                 # chẩn đoán
```

Cập nhật image mới (sau khi build lại):
```bash
docker compose build product-catalog-service
kind load docker-image bccs-product-catalog-product-catalog-service:latest --name bccs-test
kubectl -n bccs rollout restart deploy/product-catalog-service
```

Xoá sạch:
```bash
kubectl delete -f k8s/test/ ; kind delete cluster --name bccs-test
```

---

## 6. SỰ CỐ ĐÃ GẶP & CÁCH XỬ LÝ (ghi lại để tái lập)

| Sự cố | Nguyên nhân | Cách xử lý |
|---|---|---|
| **Kafka CrashLoop (Error)** | KRaft controller quorum trỏ `1@kafka:9093` (tên Service). ClusterIP Service chỉ có endpoint khi pod **Ready** → pod chưa Ready thì controller không tự kết nối được → **deadlock** | Đổi sang **`KAFKA_CONTROLLER_QUORUM_VOTERS=1@localhost:9093`** (single-node tự nối qua localhost, không phụ thuộc Service) |
| catalog/management kẹt `Init:0/1` | initContainer chờ `kafka:9092` mà kafka chưa lên | Tự khỏi sau khi sửa kafka; initContainer là đúng thiết kế (chờ phụ thuộc) |
| `ImagePullBackOff` / `ErrImageNeverPull` | Quên `kind load docker-image` | Nạp image vào kind, đặt `imagePullPolicy: IfNotPresent` |
| Pod `Pending` (thiếu RAM) | Tổng requests > RAM Docker | Giảm replica=1, hạ requests; tăng RAM Docker Desktop |

---

## 7. KHÁC BIỆT GIỮA CỤM TEST (kind) VÀ PRODUCTION

| Khía cạnh | Test (`k8s/test/`) | Production (`<service>/k8s/` + kế hoạch) |
|---|---|---|
| Replica | 1 mỗi thành phần | gateway 3+, catalog 4+ (HPA tới 20–30) |
| Lưu trữ | `emptyDir` (mất khi restart) | **PVC/StorageClass**, DB dùng dịch vụ HA |
| Infra | redis/kafka/mariadb single-node trong cụm | **Redis Cluster, Kafka 3 broker RF3, MariaDB Primary+Replica+MaxScale** (hoặc managed) |
| Auto-scale | không (kind không có metrics-server) | **HPA** (CPU + RPS) + `metrics-server`/Prometheus Adapter |
| Secret | Secret tĩnh trong manifest | **Vault / Sealed Secrets / External Secrets** |
| Log | `SPRING_PROFILES_ACTIVE=local` (tắt Logstash) | ship JSON → **Logstash/ELK** |
| Vào hệ thống | NodePort | **Ingress + Cloud LB/F5**, TLS, WAF |
| HA/DR | 1 node | đa AZ, PDB, `maxUnavailable:0`, multi-region DR |

> Để triển khai production: dùng manifest ở `<service>/k8s/` (đã có deployment/service/configmap/hpa),
> tạo Secret out-of-band (`k8s/secrets.example.yaml`), thêm Ingress + StorageClass + metrics-server,
> và đẩy image lên registry thật (đổi `image:` từ `bccs-product-catalog-*:latest` sang `registry/…:4.0.0`).
> Tham khảo [KE-HOACH-HA-TANG-PRODUCTION.md](KE-HOACH-HA-TANG-PRODUCTION.md) cho sizing.

---

## 8. TÓM TẮT KIẾN TRÚC TRIỂN KHAI (cụm test)

```
  host:18080 ──NodePort 30080──> [api-gateway pod]
                                       │ (ClusterIP services, cùng namespace "bccs")
        ┌──────────────┬───────────────┼─────────────────┐
        ▼              ▼               ▼                 ▼
  product-catalog  product-mgmt   integration        (initContainers chờ phụ thuộc)
        │   ▲           │
   reads│   │evict      │writes + events
        ▼   │           ▼
     [mariadb]      [kafka] ──topic catalog.changes──> catalog consumer
        ▲              
     [redis] (L2 cache)
```
Tất cả chạy trên 1 node kind `bccs-test-control-plane`, namespace `bccs`.
