# MariaDB ngoài cụm (chạy trên máy) cho cụm kind

Tách DB ra khỏi K8s: MariaDB chạy bằng **Docker trên máy**, app trong cụm **kind** trỏ tới qua
Service `mariadb` (ExternalName). Schema chỉ còn **một nguồn** (`schema.sql` mount thẳng).

```
[pod app trong kind] ──DB_HOST=mariadb──▶ Service ExternalName ──▶ container "bccs-mariadb-ext"
                                          (05-external-db.yaml)      trên mạng "kind" (compose này)
```

## Yêu cầu
- Cụm kind đã tồn tại (`kind get clusters` → thấy `bccs-test`). kind tạo sẵn mạng Docker tên `kind`.

## Các bước

### 1) Dựng MariaDB ngoài (gắn vào mạng kind)
```bash
cd scripts/external-db
docker compose up -d
docker compose logs -f mariadb     # chờ dòng "ready for connections"; Ctrl+C để thoát log
```
Kiểm tra container đã ở trên mạng `kind`:
```bash
docker inspect -f '{{range $k,$v := .NetworkSettings.Networks}}{{$k}} {{$v.IPAddress}}{{end}}' bccs-mariadb-ext
# phải thấy: kind 172.18.0.x
```

### 2) Khai báo Service trỏ ra DB ngoài + dựng phần còn lại
```bash
# từ thư mục gốc dự án
kubectl apply -f k8s/test/00-namespace-config.yaml   # namespace + config + secret (KHÔNG còn schema)
kubectl apply -f k8s/test/05-external-db.yaml         # Service "mariadb" -> bccs-mariadb-ext
kubectl apply -f k8s/test/10-infra.yaml               # CHỈ Redis + Kafka (MariaDB đã tách)
kubectl -n bccs rollout status deploy/redis
kubectl -n bccs rollout status deploy/kafka
kubectl apply -f k8s/test/20-services.yaml            # 4 service app
```

### 3) Kiểm tra pod app nối được DB ngoài
```bash
kubectl -n bccs get pods                 # chờ 6/6 Running (không còn pod mariadb)
# Test phân giải + cổng từ trong cụm:
kubectl -n bccs run dbtest --rm -it --image=busybox --restart=Never -- \
  sh -c "nc -zv mariadb 3306"
# "open" = pod gọi được DB ngoài qua tên mariadb. Tốt.
```

## Truy cập DB từ host (tool)
Cổng publish **3307**:
```
host=localhost  port=3307  db=bccs_catalog  user=bccs_app  pass=Bccs_App_2026
```

## Đổi schema (thêm bảng) — KHÔNG còn đụng file 00
DB ngoài chỉ chạy `schema.sql` **lần đầu** khi volume trống. Sau đó:
```bash
# nạp/đổi bảng thẳng vào DB ngoài
docker exec -i bccs-mariadb-ext mariadb -ubccs_app -pBccs_App_2026 bccs_catalog \
  < ../../product-catalog-service/src/main/resources/db/schema.sql
```
(File dùng `CREATE TABLE IF NOT EXISTS` nên chạy lại an toàn.) Hoặc dựng lại sạch:
`docker compose down -v && docker compose up -d`.

## Gỡ
```bash
cd scripts/external-db && docker compose down       # giữ data (volume)
docker compose down -v                              # xóa luôn data
```

---

## Khắc phục nếu pod KHÔNG phân giải được tên `bccs-mariadb-ext`
ExternalName dựa vào việc CoreDNS forward query ra Docker DNS. Nếu môi trường không cho (pod báo
`UnknownHost`), chuyển sang **trỏ thẳng IP** container:

1. Lấy IP container trên mạng kind:
   ```bash
   docker inspect -f '{{.NetworkSettings.Networks.kind.IPAddress}}' bccs-mariadb-ext   # vd 172.18.0.5
   ```
2. Thay nội dung `k8s/test/05-external-db.yaml` bằng Service headless + EndpointSlice trỏ IP đó:
   ```yaml
   apiVersion: v1
   kind: Service
   metadata: { name: mariadb, namespace: bccs }
   spec:
     ports: [{ name: mysql, port: 3306, targetPort: 3306 }]
     clusterIP: None
   ---
   apiVersion: discovery.k8s.io/v1
   kind: EndpointSlice
   metadata:
     name: mariadb-1
     namespace: bccs
     labels: { kubernetes.io/service-name: mariadb }
   addressType: IPv4
   ports: [{ name: mysql, port: 3306 }]
   endpoints:
     - addresses: ["172.18.0.5"]    # <-- IP container ở bước 1
   ```
3. `kubectl apply -f k8s/test/05-external-db.yaml` rồi `kubectl -n bccs rollout restart deploy`.
   (Nhược: IP đổi khi container dựng lại thì phải sửa lại — chấp nhận cho test.)
