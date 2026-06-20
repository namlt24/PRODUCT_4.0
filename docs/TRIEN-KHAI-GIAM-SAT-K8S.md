# HƯỚNG DẪN TRIỂN KHAI GIÁM SÁT (Observability) TRÊN K8S

> Cài đặt & cấu hình stack giám sát **trỏ vào cụm kind `bccs-test`** đã dựng:
> **metrics-server, Prometheus, Grafana, Elasticsearch, Kibana, Filebeat, Elastic APM**.
> Tất cả nằm ở namespace `monitoring`. Manifest ở **`k8s/monitoring/`**. Đã chạy & **test thực tế**
> (kết quả ở mục 6).
>
> Đọc kèm: [TRIEN-KHAI-K8S.md](TRIEN-KHAI-K8S.md) (triển khai ứng dụng).

---

## 1. THÀNH PHẦN & VAI TRÒ

| Thành phần | Vai trò | Manifest |
|---|---|---|
| **metrics-server** | Cấp CPU/RAM cho `kubectl top` + Dashboard + HPA | cài từ upstream + patch kind |
| **Prometheus** | Thu thập metrics (JVM, HikariCP, cache, Kafka lag, HTTP) | `10-prometheus-grafana.yaml` |
| **Grafana** | Dashboard số liệu (datasource Prometheus + ES) | `10-prometheus-grafana.yaml` |
| **Elasticsearch** | Lưu log tập trung + dữ liệu APM | `20-elk.yaml` |
| **Kibana** | UI xem log + APM | `20-elk.yaml` |
| **Filebeat** (DaemonSet) | Thu log JSON từ container → ES | `20-elk.yaml` |
| **APM Server + Java agent** | Distributed tracing (transaction/span) | `30-apm.yaml` + patch deployment |

```
   [pods bccs] --/actuator/prometheus(annotation)--> [Prometheus] --> [Grafana]
        │ stdout JSON
        └--> [Filebeat DaemonSet] --decode_json--> [Elasticsearch] --> [Kibana]
        │ (Elastic APM Java agent, -javaagent)
        └--> [APM Server] --------------------------> [Elasticsearch] --> [Kibana APM]
```

---

## 2. CÁC BƯỚC TRIỂN KHAI

```bash
kubectl config use-context kind-bccs-test

# (1) metrics-server (+ patch cho kind: kubelet cert tự ký)
kubectl apply -f https://github.com/kubernetes-sigs/metrics-server/releases/latest/download/components.yaml
kubectl patch -n kube-system deploy metrics-server --type=json \
  -p='[{"op":"add","path":"/spec/template/spec/containers/0/args/-","value":"--kubelet-insecure-tls"}]'

# (2) Prometheus + Grafana
kubectl apply -f k8s/monitoring/10-prometheus-grafana.yaml
# Gắn annotation để Prometheus tự phát hiện & scrape pod bccs:
for s in "product-catalog-service 8081" "product-management-service 8082" "integration-service 8083" "api-gateway 8080"; do
  set -- $s
  kubectl -n bccs patch deploy $1 -p '{"spec":{"template":{"metadata":{"annotations":{"prometheus.io/scrape":"true","prometheus.io/path":"/actuator/prometheus","prometheus.io/port":"'$2'"}}}}}'
done

# (3) ELK
kubectl apply -f k8s/monitoring/20-elk.yaml

# (4) APM Server + gắn agent vào 4 service (xem k8s/monitoring/30-apm.yaml để biết patch)
kubectl apply -f k8s/monitoring/30-apm.yaml
# (patch agent: thêm initContainer tải elastic-apm-agent.jar + env JAVA_TOOL_OPTIONS — script ở mục 5)

# (5) Kiểm tra
kubectl -n monitoring get pods
kubectl top pods -n bccs
```

---

## 3. CƠ CHẾ HOẠT ĐỘNG (điểm cần hiểu)

- **Prometheus — service discovery theo annotation:** job `bccs-pods` dùng `kubernetes_sd_configs`
  role `pod`, `relabel` để **chỉ giữ pod có `prometheus.io/scrape=true`** và lấy `path`/`port` từ
  annotation. Không cần ServiceMonitor.
- **Filebeat — đọc log container + giải mã JSON:** input `container` đọc `/var/log/containers/*_bccs_*.log`
  (chỉ namespace bccs), `add_kubernetes_metadata` gắn nhãn pod, **`decode_json_fields`** parse dòng
  JSON của app thành field (`traceId`, `serviceName`, `level`…) → index `bccs-logs-YYYY.MM.DD`.
- **APM — gắn agent KHÔNG sửa code:** initContainer tải `elastic-apm-agent.jar` vào volume chung;
  container chính nhận `JAVA_TOOL_OPTIONS=-javaagent:/apm/elastic-apm-agent.jar` + `ELASTIC_APM_*`
  → agent tự instrument Spring Web MVC, gửi transaction/span tới APM Server → ES.

---

## 4. TRUY CẬP UI (qua port-forward)

```bash
# Grafana  -> http://localhost:3000  (admin / admin)
kubectl -n monitoring port-forward svc/grafana 3000:3000
# Kibana   -> http://localhost:5601  (Discover xem log; Observability > APM xem trace)
kubectl -n monitoring port-forward svc/kibana 5601:5601
# Prometheus -> http://localhost:9090  (Status > Targets; thử query)
kubectl -n monitoring port-forward svc/prometheus 9090:9090
```
Trong Kibana: tạo Data View `bccs-logs-*` (timeField `@timestamp`) để xem log; APM data ở
`traces-apm*` / `metrics-apm*`.

Truy vấn Prometheus hữu ích:
```
jvm_memory_used_bytes{app="product-catalog-service"}
hikaricp_connections_active
rate(http_server_requests_seconds_count[1m])
kafka_consumer_fetch_manager_records_lag
```

---

## 5. SCRIPT GẮN APM AGENT (tham khảo)

```bash
for svc in product-catalog-service product-management-service integration-service api-gateway; do
  kubectl -n bccs patch deploy $svc --type=strategic -p '{"spec":{"template":{"spec":{
    "volumes":[{"name":"apm-agent","emptyDir":{}}],
    "initContainers":[{"name":"apm-download","image":"curlimages/curl:8.7.1","command":["sh","-c",
      "curl -fsSL --retry 6 -o /apm/elastic-apm-agent.jar https://repo1.maven.org/maven2/co/elastic/apm/elastic-apm-agent/1.49.0/elastic-apm-agent-1.49.0.jar"],
      "volumeMounts":[{"name":"apm-agent","mountPath":"/apm"}]}],
    "containers":[{"name":"'$svc'","env":[
       {"name":"JAVA_TOOL_OPTIONS","value":"-javaagent:/apm/elastic-apm-agent.jar"},
       {"name":"ELASTIC_APM_SERVER_URL","value":"http://apm-server.monitoring:8200"},
       {"name":"ELASTIC_APM_SERVICE_NAME","value":"'$svc'"},
       {"name":"ELASTIC_APM_ENVIRONMENT","value":"test"},
       {"name":"ELASTIC_APM_APPLICATION_PACKAGES","value":"com.telecom.bccs"}
    ],"volumeMounts":[{"name":"apm-agent","mountPath":"/apm"}]}]
  }}}}'
done
```
> An toàn: dùng rolling update — nếu tải agent lỗi, pod mới kẹt Init còn **pod cũ vẫn phục vụ**.

---

## 6. KẾT QUẢ TEST THỰC TẾ (2026-06-20)

| Thành phần | Cách test | Kết quả |
|---|---|---|
| **metrics-server** | `kubectl top nodes/pods` | ✅ Node 4.5Gi/58%; thấy CPU/RAM từng pod |
| **Prometheus** | `/api/v1/targets`, query metric | ✅ **5 target UP**; có `hikaricp_connections`, `jvm_memory_used_bytes` cho cả 4 service |
| **ELK (log)** | sinh traffic → query ES | ✅ index `bccs-logs-*` **1438+ docs**; field `serviceName/level/traceId` đã parse |
| **APM (trace)** | sinh traffic → query ES | ✅ `traces-apm-default` **140 traces** + `metrics-apm.app.*` cho 4 service; transaction `Spring Web MVC`, có `trace.id` |
| **App vẫn ổn định** | `kubectl get pods` | ✅ bccs **7/7 Ready** trong suốt quá trình |

---

## 7. SỰ CỐ ĐÃ GẶP & XỬ LÝ

| Sự cố | Nguyên nhân | Cách xử lý |
|---|---|---|
| **APM Server không ingest** (`traces-apm not found`, 404) | APM 8.x yêu cầu "apm integration" index template (thường cài qua **Kibana Fleet**) | Thêm **`apm-server.data_streams.wait_for_integration: false`** → APM Server tự tạo template + data stream |
| Aggregation `service.name` lỗi (`use a keyword field`) | Standalone (không Fleet) → field map kiểu `text`, không phải `keyword` ECS | Trace **vẫn ingest & xem được**; muốn UI APM/aggregation chuẩn → cài **APM integration qua Kibana Fleet** (mục 8) |
| metrics-server `readyReplicas` rỗng | kind dùng kubelet cert tự ký | patch `--kubelet-insecure-tls` |
| ES cần `vm.max_map_count` | yêu cầu hệ thống của ES | initContainer privileged `sysctl -w vm.max_map_count=262144` |

---

## 8. HẠN CHẾ & HƯỚNG PRODUCTION

- **APM mapping chuẩn:** production nên cài **APM integration qua Kibana Fleet** (Stack Management →
  Fleet → APM) hoặc **Elastic Agent**, để field map ECS đúng (keyword) → đầy đủ UI APM (service map,
  latency, error rate, dependency).
- **Elasticsearch HA:** test là single-node `emptyDir`. Production: **cụm ES 3 node** (master/data),
  **PVC**, snapshot/backup, **ILM** (rollover + xoá index cũ) vì log rất tốn dung lượng.
- **Retention:** Prometheus test giữ 3h + `emptyDir`. Production: PVC + **Thanos/Mimir** dài hạn,
  Alertmanager + rule cảnh báo (p99 latency, error rate, Kafka lag, HikariCP).
- **Tài nguyên:** test siết heap (ES 512m, services +agent). Production sizing theo
  [KE-HOACH-HA-TANG-PRODUCTION.md](KE-HOACH-HA-TANG-PRODUCTION.md) (ELK là thành phần tốn nhất).
- **Bảo mật:** bật xpack.security cho ES/Kibana, TLS, tài khoản; APM token; không expose NodePort trần.

---

## 9. GỠ BỎ

```bash
kubectl delete -f k8s/monitoring/          # xoá stack giám sát
# gỡ agent khỏi service (rollback patch):
for s in product-catalog-service product-management-service integration-service api-gateway; do
  kubectl -n bccs rollout undo deploy/$s
done
```
