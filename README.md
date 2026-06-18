# BCCS Product Catalog Platform 4.0

High-performance, cloud-native telecom **Product Catalog** for 3rd-party partners.
Sub-millisecond reads via a two-level cache, event-driven cache invalidation, and a
full observability stack.

> **Stack:** Java 21 · Spring Boot 3.2 · Spring Cloud Gateway 2023.0 · MariaDB · Redis ·
> Apache Kafka · Caffeine · Resilience4j · Micrometer Tracing · Docker · Kubernetes ·
> Prometheus/Grafana + ELK.

---

## 1. Architecture

```
                3rd-party partners (traceparent header)
                              │
                    ┌─────────▼──────────┐
                    │    api-gateway     │  routing · Redis rate-limit (per clientId)
                    │ (Spring Cloud GW)  │  JWT auth (in-memory pubkey / introspection)
                    │  Resilience4j CB   │  trace-id inject → X-Trace-Id / traceparent
                    └───┬─────────┬──────┘
          read │ /products,/offers       │ /management/**          │ /integration/**
               ▼                          ▼                         ▼
    product-catalog-service     product-management-service    integration-service
    Caffeine L1 + Redis L2       CRUD + publish Kafka event     JWT validate /
    cache-aside, fail-open       (after commit)                 3rd-party introspect
               ▲                          │
               │   Kafka: catalog.changes │ (cache invalidation)
               └──────────────────────────┘
                              │
                    MariaDB (shared schema)  ·  Redis  ·  Kafka
                              │
        Observability: Micrometer → Prometheus/Grafana · JSON logs → Logstash → ES → Kibana
```

### Services & ports
| Service | Port | Role |
|---|---|---|
| api-gateway | 8080 | Single entry point, rate limit, auth, tracing, circuit breaker |
| product-catalog-service | 8081 | Read-heavy; two-level cache; Kafka cache-invalidation consumer |
| product-management-service | 8082 | Write/CRUD; publishes change events |
| integration-service | 8083 | 3rd-party auth integration; JWT validation/introspection |

---

## 2. Run locally

```bash
cp .env.example .env          # fill DB password, JWT public key, etc.
docker compose up -d --build
```

| UI | URL |
|---|---|
| Gateway | http://localhost:8080 |
| Catalog Swagger UI | http://localhost:8081/swagger-ui.html |
| Prometheus | http://localhost:9090 |
| Grafana | http://localhost:3000 (admin / `$GRAFANA_ADMIN_PASSWORD`) |
| Kibana | http://localhost:5601 |

Example call through the gateway:
```bash
curl -H "Authorization: Bearer <jwt>" \
     -H "traceparent: 00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01" \
     http://localhost:8080/api/v1/products/123
```

---

## 3. Non-functional design highlights

### Caching (sub-ms reads, DB protection)
- **L1 Caffeine** (in-process, 30s TTL) → **L2 Redis** (random TTL) → **DB** cache-aside.
  See `TwoLevelCacheService`.
- **Cache Avalanche** guard: every Redis TTL = `base-ttl` + random jitter
  (`cache.l2.base-ttl-seconds` + `cache.l2.jitter-seconds`), so a partner's bulk scan
  doesn't make millions of keys expire in the same second.
- **Cache Penetration** guard: a `__NULL__` marker is cached (short `null-ttl-seconds`)
  for non-existent ids, so repeated bad-id requests never reach the DB.
- **Cache invalidation**: writes publish `catalog.changes`; the catalog consumer evicts the
  exact key in both levels (trace id propagated via Kafka headers).

### Connection pooling (avoid Oracle/MariaDB starvation)
HikariCP sized per service so the **sum stays below MariaDB `max_connections`**:
catalog read pool `40`, management write pool `20`, plus headroom. `max-lifetime`
is kept under the server `wait_timeout`.

### Resilience — no single failure takes the platform down
- **Redis down** → catalog cache calls fail-open to the DB (`safeRedisGet/Put`); readiness
  probe still passes (`readiness.include: db,ping`).
- **Kafka down** → reads unaffected (serve slightly staler cache); the producer retries with
  idempotence; the consumer uses bounded retry + error handler so a poison message can't
  block a partition.
- **3rd-party auth down** → Resilience4j circuit breaker at the gateway / integration-service
  opens and returns a controlled response instead of cascading (`FallbackController`).
- **Pod/node loss** → K8s replicas (`minReplicas ≥ 2`), `maxUnavailable: 0` rollouts,
  topology spread, HPA scale-out.

### Security
- **No DB credentials in YAML** — injected via env vars / K8s `Secret` (`bccs-secrets`).
- Asymmetric JWT validated in-memory with a public key (fast path); remote introspection
  fallback.

### Observability
- **Micrometer Tracing (W3C)** replaces Sleuth; gateway accepts/!generates `traceparent`,
  injects `X-Trace-Id`/`X-Client-Id`, propagates over HTTP + Kafka headers.
- **JSON logs** (`timestamp, traceId, spanId, clientId, serviceName, level, message`) →
  Logstash (`monitoring/logstash/pipeline/logstash.conf`) → Elasticsearch → Kibana.
- **Prometheus** scrapes `/actuator/prometheus` on every service: JVM, HikariCP pool,
  Caffeine hit/miss, Kafka consumer lag, request rate/latency.

### Daily DB backup & rollback (required)
- `scripts/db-backup/backup.sh` — consistent `mariadb-dump --single-transaction`, gzip,
  integrity check, 14-day rotation, optional S3 off-site.
- `scripts/db-backup/k8s-cronjob.yaml` — runs daily at 02:00 to a PVC.
- `scripts/db-backup/restore.sh` — guided rollback from any dump (then flush Redis).

---

## 4. Build & deploy

```bash
# Build all modules
mvn -q clean package

# Build images (per service Dockerfile, multi-stage, Temurin JRE)
docker build -f product-catalog-service/Dockerfile -t registry.local/bccs/product-catalog-service:4.0.0 .

# Kubernetes
kubectl apply -f k8s/namespace.yaml
kubectl -n bccs create secret generic bccs-secrets --from-literal=DB_USER=... --from-literal=DB_PASSWORD=...
kubectl apply -f api-gateway/k8s/
kubectl apply -f product-catalog-service/k8s/
kubectl apply -f product-management-service/k8s/
kubectl apply -f integration-service/k8s/
kubectl apply -f scripts/db-backup/k8s-cronjob.yaml
```

---

## 5. Module layout
```
common-lib/                  shared tracing, MDC constants, ApiError
api-gateway/                 routing, rate limit, auth, trace, fallback
product-catalog-service/     read + two-level cache + kafka consumer + openapi
product-management-service/  write/CRUD + kafka producer
integration-service/         3rd-party auth / JWT validation
monitoring/                  prometheus, logstash, grafana provisioning
scripts/db-backup/           backup/restore + k8s cronjob
k8s/                         namespace + secret template
```

## 6. Notes / production follow-ups
- For HA infra, switch local single Redis/Kafka to **Redis Cluster (or Sentinel)** and a
  **3-broker Kafka** with replication factor ≥ 3; the code already targets cluster-friendly
  clients and degrades gracefully.
- The transactional event publish uses after-commit hooks; for exactly-once guarantees add a
  **transactional outbox** table polled by a relay.
