# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

High-performance telecom **Product Catalog** platform for 3rd-party partners. Multi-module
Maven monorepo (4 Spring Boot services + a shared library) plus DevOps/observability config.
Read path targets sub-millisecond latency via a two-level cache; writes propagate as Kafka
events that invalidate the read cache. Stack: **Java 21, Spring Boot 3.2, Spring Cloud
Gateway 2023.0.1, MariaDB, Redis, Kafka, Caffeine, Resilience4j, Micrometer Tracing**.

## Critical environment facts

- **Local JDK is 17, but the project targets Java 21.** Do NOT run `mvn package` on the host —
  it will fail to compile. All builds happen inside Docker (`maven:3.9-eclipse-temurin-21`).
  An IDE may compile `target/classes` in the background; treat that as advisory only.
- Primary shell is **PowerShell**; a Git Bash tool is also available. In Git Bash, native
  Linux paths passed to `docker exec` get mangled (`/opt/...` → `C:/Program Files/Git/opt/...`).
  Prefix such commands with `MSYS_NO_PATHCONV=1`.
- Host port **3306 is taken** by a local MariaDB/MySQL; the compose MariaDB is published on
  **3307** instead (containers still talk to `mariadb:3306` over the compose network).

## Build, run, demo (single-node, no K8s)

```bash
cp .env.example .env            # fill DB_PASSWORD etc. (.env already exists for local demo)

# Build images — pipe to tail MASKS the real exit code, always use pipefail:
set -o pipefail; docker compose build <service> 2>&1 | tail -25; echo "EXIT=${PIPESTATUS[0]}"

# Bring up core stack (infra auto-pulled via depends_on)
docker compose up -d mariadb redis kafka
docker compose up -d api-gateway product-catalog-service product-management-service integration-service

docker compose ps                        # check health
docker compose logs -f product-catalog-service
docker compose down                      # stop (keeps named volumes)
```

The heavy observability cluster (Elasticsearch, Logstash, Kibana, Prometheus, Grafana) is
defined in `docker-compose.yml` but **omitted from the core demo** (ES is RAM-hungry). Logging
to Logstash is fail-safe: if Logstash is absent the TCP appender just warns and retries, the
app keeps running. Start the cluster explicitly when needed.

### Ports
gateway `8080` · catalog `8081` · management `8082` · integration `8083` · MariaDB host `3307`
· redis `6379` · kafka `9092`. Swagger UI: `http://localhost:8081/swagger-ui.html`.

### Tests
Standard `mvn -pl <module> test` — but must run in the Java 21 toolchain (container), not host.

## Architecture (the parts that span files)

**Request path:** partner → `api-gateway` → service. The gateway is the only WebFlux/reactive
module; the three downstream services are servlet-based (Java 21 virtual threads enabled).

**Two-level cache** (`product-catalog-service`): the heart of the read path is
`service/TwoLevelCacheService.java`, NOT Spring's `@Cacheable`. It does cache-aside L1 Caffeine
→ L2 Redis → DB loader, with three deliberate protections baked in:
- *Avalanche*: every Redis TTL = base + random jitter (`cache.l2.*` props).
- *Penetration*: a `NULL_MARKER` ("`__NULL__`") is cached for missing ids.
- *Redis outage*: all Redis ops are wrapped and **fail open** to the DB; readiness probe only
  depends on `db,ping` so a degraded Redis/Kafka does not make pods un-ready.
- Note: this class keeps its OWN Caffeine instance for full L1↔L2 control. The Spring
  `CacheConfig`/`CacheManager` caches (`l1Products`/`l1Offers`) are currently **unused**, so
  Micrometer's `cache_gets_total` reads zero. Real hit/miss lives in the custom instance and
  is not yet bound to the `MeterRegistry`.

**Cache invalidation is event-driven, not TTL-only.** `product-management-service` publishes a
`CatalogChangeEvent` to topic `catalog.changes` — but only **after the DB transaction commits**
(`ProductManagementService` registers a `TransactionSynchronization.afterCommit`). The catalog's
`kafka/CatalogChangeConsumer` evicts the exact key in both cache levels. Each service has its
OWN copy of `CatalogChangeEvent` (producer side in `management`, consumer side in `catalog`) —
they are deliberately decoupled, JSON-compatible records; keep their fields in sync when editing.

**Distributed tracing is manual + W3C.** There is no Sleuth. `common-lib` holds `TraceContext`
(parse/generate `traceparent`) and `MdcConstants` (the canonical MDC / HTTP-header / Kafka-header
key names — change them here, not per service). The gateway's `TraceContextGlobalFilter` accepts
an inbound `traceparent` or generates a UUID-v4 trace id, then injects `X-Trace-Id`/`X-Client-Id`
headers downstream. Each servlet service re-hydrates the MDC from those headers via its own
`web/TraceContextFilter`. The trace id rides Kafka record headers too, so a cache eviction log
line correlates back to the originating write. Logback emits JSON with
`traceId/spanId/clientId/serviceName` fields.

**Auth** (`api-gateway` + `integration-service`): the gateway's `AuthenticationFilter` validates
JWTs **in-memory** against an RSA public key (fast path, no network) when `JWT_PUBLIC_KEY` is set;
otherwise it falls back to remote introspection at `integration-service`, wrapped in a Resilience4j
circuit breaker. `permit-all` paths bypass auth. In the demo `JWT_PUBLIC_KEY` is empty, so calls
*through the gateway* return 401 — demo the cache/CRUD by hitting catalog (8081) / management
(8082) directly, which do not enforce auth themselves.

**DB is a shared schema** (`bccs_catalog`): catalog reads and management writes both map the same
`product`/`offer` tables (separate `@Entity` classes per service). `ddl-auto=validate` — schema is
created from `product-catalog-service/src/main/resources/db/schema.sql`, mounted into MariaDB's
init dir by compose. HikariCP pools are sized per service so their **sum stays below MariaDB
`max_connections`** (catalog read pool 40, management write pool 20) to avoid connection starvation.

## Conventions / gotchas when editing

- Spring `@Value("${...}")` can NOT bind a YAML list; use a **comma-separated string** for
  `List<String>` injection (see `gateway.auth.permit-all`). A YAML sequence there fails startup.
- Redis values are records (final classes). `GenericJackson2JsonRedisSerializer` won't embed type
  info for final classes, so reads come back as `Map`; `TwoLevelCacheService.unwrap` coerces via
  `ObjectMapper.convertValue`. Preserve that fallback if you touch serialization.
- `resilience4j-spring-boot3` annotations (`@CircuitBreaker`/`@Retry`) require
  `spring-boot-starter-aop` on the classpath (present in `integration-service`).
- Each Dockerfile `COPY . .` then builds only its module via `-pl common-lib,<module> -am`; the
  whole reactor must be copyable for the parent POM's module list to resolve. `.dockerignore`
  excludes `**/target`, `monitoring`, `scripts`, `**/k8s`, etc.
- Group/package root is `com.telecom.bccs`; per-service subpackage = `gateway` / `catalog` /
  `management` / `integration`; shared code is `common`.

## Deliverables outside the JVM build

- K8s manifests per service in `<service>/k8s/` (`deployment/service/configmap/hpa`), shared
  `k8s/namespace.yaml` + `k8s/secrets.example.yaml` (Secret is created out-of-band, never committed).
- Daily DB backup: `scripts/db-backup/backup.sh` (+ `restore.sh`, `k8s-cronjob.yaml`).
- Observability config in `monitoring/` (prometheus scrape, logstash pipeline, grafana datasource).
