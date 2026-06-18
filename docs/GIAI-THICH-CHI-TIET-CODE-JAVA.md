# GIẢI THÍCH CHI TIẾT TỪNG DÒNG CODE JAVA — BCCS PRODUCT CATALOG 4.0

> Tài liệu giải thích **từng file Java**: làm gì, từng dòng/khối quan trọng có ý nghĩa gì,
> liên kết (gọi tới / được gọi bởi) đi đâu, và mục đích trong kiến trúc tổng thể.
>
> Đọc kèm: [TAI-LIEU-BAO-VE-KIEN-TRUC.md](TAI-LIEU-BAO-VE-KIEN-TRUC.md) (lý do thiết kế).

---

## PHẦN 0 — BẢN ĐỒ LUỒNG DỮ LIỆU (các file liên kết nhau thế nào)

**Luồng ĐỌC (đối tác lấy dữ liệu):**
```
Đối tác --HTTP--> [api-gateway]
   TraceContextGlobalFilter  → sinh/nhận traceId, inject header X-Trace-Id
   AuthenticationFilter      → verify JWT (JwtPublicKeyProvider) / introspection
   RateLimiterConfig         → giới hạn theo clientId
        │ định tuyến (application.yml routes)
        ▼
[product-catalog-service] ProductController
        → ProductCatalogService.getProduct()
            → TwoLevelCacheService.get()   [L1 Caffeine → L2 Redis → loader]
                → ProductRepository (JPA) → MariaDB   (chỉ khi cache miss)
```

**Luồng GHI + hủy cache (event-driven):**
```
Đối tác --HTTP--> [api-gateway] --> [product-management-service]
   ProductManagementController → ProductManagementService.create/update/delete()
        → ProductRepository (JPA) → MariaDB  (trong transaction)
        → publishAfterCommit()  [SAU commit]
            → CatalogEventPublisher.publish() → Kafka topic "catalog.changes"
                                          │
                                          ▼
[product-catalog-service] CatalogChangeConsumer.onCatalogChange()
        → TwoLevelCacheService.evict()  [xóa L1 + L2]
```

**Thư viện chung** (`common-lib`) cung cấp `TraceContext`, `MdcConstants`, `ApiError` cho **cả 4 service**.

---

# PHẦN A — common-lib (thư viện dùng chung)

## A.1. `common/tracing/MdcConstants.java`

**Mục đích:** Nơi DUY NHẤT định nghĩa tên các "khóa" dùng cho log (MDC), HTTP header và Kafka
header. Mọi service tham chiếu tới đây → đổi tên ở một chỗ, toàn hệ thống đồng bộ.

```java
public final class MdcConstants {
    private MdcConstants() {}                       // final class + private constructor
```
- `final class` + constructor `private`: đây là lớp tiện ích chỉ chứa hằng số, **cấm khởi tạo**
  và **cấm kế thừa** → tránh dùng sai.

```java
    public static final String W3C_TRACEPARENT = "traceparent";
```
- Tên header chuẩn **W3C Trace Context** mà đối tác gửi vào (định dạng `00-<trace>-<span>-<flags>`).

```java
    public static final String MDC_TRACE_ID = "traceId";
    public static final String MDC_SPAN_ID  = "spanId";
    public static final String MDC_CLIENT_ID = "clientId";
    public static final String MDC_USER_ID  = "userId";
    public static final String MDC_SERVICE_NAME = "serviceName";
```
- Các khóa **MDC** (Mapped Diagnostic Context của SLF4J). Khi đặt `MDC.put("traceId", ...)`,
  Logback chèn đúng các khóa này thành **trường JSON** trong log → Kibana lọc được.

```java
    public static final String HEADER_TRACE_ID = "X-Trace-Id";
    public static final String HEADER_SPAN_ID  = "X-Span-Id";
    public static final String HEADER_CLIENT_ID = "X-Client-Id";
    public static final String HEADER_USER_ID  = "X-User-Id";
```
- Các **HTTP header nội bộ** gateway tiêm vào request khi chuyển tiếp xuống service downstream.

```java
    public static final String KAFKA_HEADER_TRACE_ID = "x-trace-id";
    public static final String KAFKA_HEADER_CLIENT_ID = "x-client-id";
}
```
- Khóa **header trên Kafka record** (giá trị `byte[]`) để mang theo ngữ cảnh trace qua hàng đợi.

**Liên kết:** Dùng bởi `TraceContextGlobalFilter`, `AuthenticationFilter`,
`TraceContextFilter` (3 service), `CatalogChangeConsumer`, `CatalogEventPublisher`,
`GlobalExceptionHandler`.

---

## A.2. `common/tracing/TraceContext.java`

**Mục đích:** Tiện ích **phân tích (parse)** và **sinh** giá trị trace theo chuẩn W3C.

```java
private static final Pattern TRACEPARENT = Pattern.compile(
        "^[0-9a-f]{2}-([0-9a-f]{32})-([0-9a-f]{16})-[0-9a-f]{2}$");
```
- Biểu thức chính quy khớp đúng format `traceparent`: 2 hex (version) - **32 hex (traceId,
  nhóm 1)** - **16 hex (spanId, nhóm 2)** - 2 hex (flags). Dùng để vừa kiểm tra hợp lệ vừa
  trích xuất.

```java
public static String extractTraceId(String traceparent) {
    if (traceparent == null) return null;
    var matcher = TRACEPARENT.matcher(traceparent.trim().toLowerCase());
    return matcher.matches() ? matcher.group(1) : null;     // group(1) = traceId
}
```
- Trả về 32-hex traceId nếu header hợp lệ; `null` nếu thiếu/sai → bên gọi sẽ tự sinh mới.
- `toLowerCase()` vì hex chuẩn W3C là chữ thường.

```java
public static String extractSpanId(String traceparent) { ... matcher.group(2) ... }
```
- Tương tự, lấy 16-hex spanId (nhóm 2).

```java
public static String newTraceId() {
    UUID u = UUID.randomUUID();
    return digits(u.getMostSignificantBits()) + digits(u.getLeastSignificantBits());
}
```
- Sinh traceId mới **32 ký tự hex** từ một UUID v4 (128 bit = 2×64 bit → 2×16 hex = 32 hex).
  Đáp ứng yêu cầu "tự sinh Trace ID (UUID v4) nếu thiếu".

```java
public static String newSpanId() { return digits(UUID.randomUUID().getMostSignificantBits()); }
```
- Sinh spanId 16 hex (64 bit).

```java
public static String buildTraceparent(String traceId, String spanId) {
    return "00-" + traceId + "-" + spanId + "-01";
}
```
- Ráp lại header `traceparent` hợp lệ để gửi tiếp downstream (`00` = version, `01` = sampled).

```java
private static String digits(long value) { return String.format("%016x", value); }
```
- Đổi 1 `long` (64 bit) thành đúng 16 ký tự hex, đệm 0 ở đầu.

**Liên kết:** Gọi bởi `TraceContextGlobalFilter` (gateway) và 3 `TraceContextFilter` ở các service.

---

## A.3. `common/web/dto/ApiError.java`

**Mục đích:** Khuôn dạng lỗi **thống nhất** trả cho đối tác. Có `traceId` để đối tác báo 1 mã là
tra được toàn bộ hành trình.

```java
public record ApiError(
        Instant timestamp, int status, String error,
        String message, String path, String traceId) {

    public static ApiError of(int status, String error, String message, String path, String traceId) {
        return new ApiError(Instant.now(), status, error, message, path, traceId);
    }
}
```
- `record`: lớp bất biến, tự sinh constructor/getter/equals/hashCode → gọn cho DTO.
- `of(...)`: factory tự gắn `timestamp = now()` để nơi gọi khỏi lặp lại.

**Liên kết:** Trả về bởi `FallbackController`, `AuthenticationFilter` (gateway) và
`GlobalExceptionHandler` (catalog, management).

---

# PHẦN B — api-gateway (cổng vào, reactive/WebFlux)

## B.1. `gateway/ApiGatewayApplication.java`

```java
@SpringBootApplication
public class ApiGatewayApplication {
    public static void main(String[] args) {
        SpringApplication.run(ApiGatewayApplication.class, args);
    }
}
```
- `@SpringBootApplication`: điểm khởi động Spring Boot (bật auto-config + quét component trong
  package `com.telecom.bccs.gateway`).
- **Liên kết:** quét và nạp tất cả `@Component/@Configuration/@RestController` của gateway
  (các filter, config, FallbackController).

---

## B.2. `gateway/config/JwtPublicKeyProvider.java`

**Mục đích:** Nạp **public key RSA một lần lúc khởi động**, giữ trong bộ nhớ để verify chữ ký JWT
cục bộ (sub-ms, không gọi mạng).

```java
@Component
public class JwtPublicKeyProvider {
    private final String base64Key;
    private volatile PublicKey publicKey;
```
- `@Component`: Spring quản lý vòng đời, có thể tiêm vào `AuthenticationFilter`.
- `volatile`: đảm bảo thread khác (request đến đa luồng) thấy giá trị `publicKey` đã set.

```java
    public JwtPublicKeyProvider(@Value("${gateway.auth.jwt-public-key:}") String base64Key) {
        this.base64Key = base64Key;
    }
```
- `@Value("${...:}")`: đọc khóa từ cấu hình/biến môi trường (`JWT_PUBLIC_KEY`); mặc định **rỗng**
  nếu không cấu hình → đúng nguyên tắc "không hardcode khóa".

```java
    @PostConstruct
    void init() {
        if (base64Key == null || base64Key.isBlank()) {
            log.warn("No JWT public key configured...");  return;       // -> dùng introspection
        }
```
- `@PostConstruct`: chạy **sau khi tiêm xong**. Nếu không có khóa → chỉ cảnh báo, để
  `AuthenticationFilter` chuyển sang xác thực từ xa (introspection).

```java
        String cleaned = base64Key.replace("-----BEGIN PUBLIC KEY-----","")
                                   .replace("-----END PUBLIC KEY-----","")
                                   .replaceAll("\\s","");
        byte[] der = Base64.getDecoder().decode(cleaned);
        this.publicKey = KeyFactory.getInstance("RSA")
                                   .generatePublic(new X509EncodedKeySpec(der));
```
- Chấp nhận cả PEM (có dòng BEGIN/END) lẫn base64 thuần: bóc header/xuống dòng → decode base64
  thành DER → dựng `PublicKey` RSA (chuẩn X.509). Đây là khóa dùng để verify chữ ký.

```java
    public boolean isLocalValidationEnabled() { return publicKey != null; }
    public PublicKey getPublicKey() { return publicKey; }
```
- Hai getter để `AuthenticationFilter` hỏi "có verify cục bộ được không?" và lấy khóa.

**Liên kết:** Được tiêm vào `AuthenticationFilter`.

---

## B.3. `gateway/config/RateLimiterConfig.java`

**Mục đích:** Định nghĩa **"khóa" để đếm rate limit** — giới hạn theo từng đối tác (clientId),
tránh 1 đối tác quét ồ ạt làm nghẽn của người khác.

```java
@Bean
public KeyResolver clientIdKeyResolver() {
    return exchange -> {
        String clientId = exchange.getRequest().getHeaders().getFirst(MdcConstants.HEADER_CLIENT_ID);
        if (clientId == null || clientId.isBlank()) {
            var remote = exchange.getRequest().getRemoteAddress();
            clientId = remote != null ? remote.getAddress().getHostAddress() : "anonymous";
        }
        return Mono.just(clientId);
    };
}
```
- `KeyResolver` là interface Spring Cloud Gateway dùng cho `RequestRateLimiter`.
- Lấy `X-Client-Id` (đã do `AuthenticationFilter` tiêm từ JWT) làm khóa đếm. Mỗi clientId có
  "xô token" riêng.
- Nếu chưa có (request chưa xác thực) → fallback theo **địa chỉ IP** để vẫn giới hạn được.
- Trả `Mono` vì gateway là reactive.

**Liên kết:** Tên bean `clientIdKeyResolver` được tham chiếu trong `application.yml`:
`key-resolver: "#{@clientIdKeyResolver}"`.

---

## B.4. `gateway/filter/TraceContextGlobalFilter.java`

**Mục đích:** Filter **chạy đầu tiên**. Dựng ngữ cảnh trace từ `traceparent` của đối tác hoặc
sinh mới, rồi tiêm header xuống downstream và đưa vào MDC để log gateway có traceId.

```java
@Component
public class TraceContextGlobalFilter implements GlobalFilter, Ordered {
```
- `GlobalFilter`: filter áp cho **mọi** route. `Ordered`: cho phép định thứ tự chạy.

```java
    String traceparent = request.getHeaders().getFirst(MdcConstants.W3C_TRACEPARENT);
    String traceId = TraceContext.extractTraceId(traceparent);
    String spanId  = TraceContext.extractSpanId(traceparent);
    if (traceId == null) traceId = TraceContext.newTraceId();   // thiếu -> sinh UUID v4
    spanId = TraceContext.newSpanId();                          // gateway luôn mở span mới
    String rebuiltTraceparent = TraceContext.buildTraceparent(traceId, spanId);
```
- Nhận traceId nếu đối tác gửi hợp lệ; nếu không → **tự sinh** (yêu cầu đề bài).
- Gateway luôn tạo **span mới** cho chặng downstream (giữ nguyên trace, đổi span = đúng mô hình
  phân tán).

```java
    ServerHttpRequest mutated = request.mutate()
        .header(MdcConstants.HEADER_TRACE_ID, traceId)
        .header(MdcConstants.HEADER_SPAN_ID, spanId)
        .header(MdcConstants.W3C_TRACEPARENT, rebuiltTraceparent)
        .build();
    exchange.getAttributes().put(MdcConstants.MDC_TRACE_ID, traceId);
```
- **Tiêm** `X-Trace-Id`, `X-Span-Id`, `traceparent` vào request gửi xuống service.
- Lưu traceId vào "attributes" của exchange để `AuthenticationFilter`/`FallbackController` đọc lại.

```java
    MDC.put(MdcConstants.MDC_TRACE_ID, fTraceId);
    MDC.put(MdcConstants.MDC_SPAN_ID, fSpanId);
    MDC.put(MdcConstants.MDC_SERVICE_NAME, "api-gateway");
    return chain.filter(exchange.mutate().request(mutated).build())
        .doFinally(signal -> {
            exchange.getResponse().getHeaders().add(MdcConstants.HEADER_TRACE_ID, fTraceId);
            MDC.clear();
        });
```
- Đặt MDC để log của gateway mang traceId.
- `doFinally`: **sau khi xử lý xong**, echo `X-Trace-Id` vào response (đối tác thấy được mã trace)
  và `MDC.clear()` để không rò rỉ ngữ cảnh sang request khác dùng chung thread.

```java
@Override public int getOrder() { return Ordered.HIGHEST_PRECEDENCE; }
```
- Ưu tiên cao nhất → chạy **trước** mọi filter khác (kể cả auth), để mọi log đều có traceId.

**Liên kết:** Dùng `TraceContext`, `MdcConstants`. Chạy trước `AuthenticationFilter`.

---

## B.5. `gateway/filter/AuthenticationFilter.java` ⭐ (cốt lõi bảo mật)

**Mục đích:** Chặn mọi request chưa xác thực; verify JWT cục bộ bằng public key (nhanh) hoặc
introspection từ xa (có circuit breaker); tiêm `X-User-Id`, `X-Client-Id` xuống downstream.

```java
@Component
public class AuthenticationFilter implements GlobalFilter, Ordered {
    private static final AntPathMatcher PATH_MATCHER = new AntPathMatcher();
```
- `AntPathMatcher`: so khớp đường dẫn kiểu `/actuator/**` cho danh sách bỏ qua xác thực.

```java
    public AuthenticationFilter(JwtPublicKeyProvider keyProvider,
                                CircuitBreakerRegistry circuitBreakerRegistry,
                                ObjectMapper objectMapper,
                                WebClient.Builder webClientBuilder,
                                @Value("${gateway.auth.introspection-uri}") String introspectionUri,
                                @Value("${gateway.auth.permit-all}") List<String> permitAll) {
        ...
        this.introspectionClient = webClientBuilder.baseUrl(introspectionUri).build();
        this.permitAll = permitAll;
    }
```
- Tiêm: bộ giữ public key, **registry circuit breaker** (Resilience4j), `ObjectMapper` (ghi JSON
  lỗi), `WebClient` (gọi introspection reactive), URI introspection và danh sách `permitAll`.
- ⚠️ `permitAll` là `List<String>` đọc từ **chuỗi phân tách dấu phẩy** (xem ghi chú sự cố: YAML
  list không bind được vào `@Value`).

```java
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String path = request.getURI().getPath();
        if (isPermitAll(path)) return chain.filter(exchange);     // bỏ qua health/metrics/fallback
```
- Đường dẫn công khai (health, prometheus, fallback) → cho qua, không cần token.

```java
        String authHeader = request.getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (authHeader == null || !authHeader.startsWith("Bearer "))
            return unauthorized(exchange, "Missing or malformed Authorization header");
        String token = authHeader.substring(7);
```
- Bắt buộc có `Authorization: Bearer <token>`; thiếu/sai → **401** ngay (demo case 401).

```java
        Mono<AuthResult> validation = keyProvider.isLocalValidationEnabled()
                ? validateLocally(token) : validateRemotely(token);
```
- **Quyết định đường xác thực:** có public key → verify cục bộ (nhanh); không → introspection.

```java
        return validation.flatMap(result -> {
            ServerHttpRequest mutated = request.mutate()
                .header(MdcConstants.HEADER_USER_ID, result.userId())
                .header(MdcConstants.HEADER_CLIENT_ID, result.clientId())
                .build();
            return chain.filter(exchange.mutate().request(mutated).build());
        }).onErrorResume(ex -> unauthorized(exchange, "Invalid or expired token"));
```
- Xác thực OK → **tiêm `X-User-Id`, `X-Client-Id`** (lấy từ claim JWT) để downstream và
  rate-limiter dùng. Lỗi → 401.

```java
    private Mono<AuthResult> validateLocally(String token) {
        return Mono.fromCallable(() -> {
            Claims claims = Jwts.parser()
                .verifyWith(keyProvider.getPublicKey())   // verify chữ ký RSA
                .clockSkewSeconds(30)                      // dung sai lệch giờ 30s
                .build().parseSignedClaims(token).getPayload();
            String userId = claims.getSubject();
            String clientId = claims.get("client_id", String.class);
            if (clientId == null) clientId = claims.get("azp", String.class);  // OAuth2 azp
            return new AuthResult(userId != null ? userId : "unknown",
                                  clientId != null ? clientId : "unknown");
        });
    }
```
- Verify **bất đối xứng tại chỗ**: chỉ cần public key, không gọi mạng → đạt mục tiêu sub-ms.
- Lấy `sub` (user), `client_id`/`azp` (mã đối tác) từ claim.

```java
    private Mono<AuthResult> validateRemotely(String token) {
        var circuitBreaker = circuitBreakerRegistry.circuitBreaker("authProvider");
        return introspectionClient.post()
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
            .retrieve().bodyToMono(Map.class)
            .timeout(Duration.ofSeconds(2))
            .transformDeferred(CircuitBreakerOperator.of(circuitBreaker))
            .handle((body, sink) -> {
                if (Boolean.TRUE.equals(body.get("active")))
                    sink.next(new AuthResult(String.valueOf(body.getOrDefault("sub","unknown")),
                                             String.valueOf(body.getOrDefault("client_id","unknown"))));
                else sink.error(new IllegalStateException("Token not active"));
            });
    }
```
- Gọi introspection sang `integration-service`, **bọc circuit breaker `authProvider`** + timeout
  2s: nếu cổng auth bên thứ 3 chậm/sập → breaker mở, trả lỗi nhanh thay vì treo (chống sập dây
  chuyền). `active=true` mới cho qua.

```java
    private Mono<Void> unauthorized(ServerWebExchange exchange, String message) {
        response.setStatusCode(HttpStatus.UNAUTHORIZED);
        String traceId = (String) exchange.getAttributes()
                .getOrDefault(MdcConstants.MDC_TRACE_ID, "unknown");
        ApiError error = ApiError.of(401, "Unauthorized", message, path, traceId);
        byte[] bytes = objectMapper.writeValueAsBytes(error);
        return response.writeWith(Mono.just(response.bufferFactory().wrap(bytes)));
    }
```
- Trả body JSON `ApiError` kèm **traceId lấy từ attributes** (do `TraceContextGlobalFilter` lưu)
  → đây là lý do response 401 chứa đúng traceId của `traceparent` đối tác gửi (demo case W3C).

```java
    @Override public int getOrder() { return Ordered.HIGHEST_PRECEDENCE + 10; }
    private record AuthResult(String userId, String clientId) {}
```
- Chạy **sau** filter trace (HIGHEST+10) nhưng trước định tuyến.
- `AuthResult`: record nội bộ gói (userId, clientId).

**Liên kết:** dùng `JwtPublicKeyProvider`, `CircuitBreakerRegistry`, `ApiError`, `MdcConstants`;
gọi sang `integration-service`. Kết quả (clientId) được `RateLimiterConfig` dùng.

---

## B.6. `gateway/controller/FallbackController.java`

**Mục đích:** Điểm "hứng" khi circuit breaker mở hoặc service downstream chết → trả **503 có kiểm
soát** thay vì treo/sập dây chuyền.

```java
@RestController
@RequestMapping("/fallback")
public class FallbackController {

    @RequestMapping(value = "/catalog", method = {RequestMethod.GET, RequestMethod.POST})
    public Mono<ResponseEntity<ApiError>> catalog(ServerWebExchange exchange) {
        return build(exchange, "product-catalog-service temporarily unavailable");
    }
```
- `@RequestMapping(method={GET,POST})`: gộp cả 2 method trong một handler (không thể đặt đồng
  thời `@GetMapping`+`@PostMapping` trên cùng method — đây là sửa lỗi đã ghi nhận).
- Các route trong `application.yml` trỏ `fallbackUri: forward:/fallback/catalog` về đây.

```java
    private Mono<ResponseEntity<ApiError>> build(ServerWebExchange exchange, String message) {
        String traceId = exchange.getResponse().getHeaders().getFirst("X-Trace-Id");
        ApiError error = ApiError.of(503, "Service Unavailable", message, path,
                                     traceId == null ? "unknown" : traceId);
        return Mono.just(ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header("Retry-After", "5").body(error));
    }
```
- Trả 503 + header `Retry-After: 5` (gợi ý đối tác thử lại sau 5s) + body `ApiError` có traceId.

**Liên kết:** Được kích hoạt bởi filter `CircuitBreaker` cấu hình trong `application.yml`.

---

# PHẦN C — product-catalog-service (ĐỌC, cache 2 tầng)

## C.1. `catalog/ProductCatalogApplication.java`

```java
@SpringBootApplication
@EnableCaching      // bật hạ tầng cache của Spring (cho CacheConfig)
@EnableKafka        // bật @KafkaListener (cho CatalogChangeConsumer)
public class ProductCatalogApplication { ... }
```
- `@EnableCaching`: kích hoạt abstraction cache (cho `CacheConfig`).
- `@EnableKafka`: kích hoạt cơ chế quét `@KafkaListener` (cho consumer hủy cache).

---

## C.2. `catalog/model/ProductDto.java` & `OfferDto.java`

```java
public record ProductDto(String id, String code, String name, String category,
                         String status, String description, Instant updatedAt)
        implements Serializable {}
```
- DTO **bất biến** trả cho đối tác và **lưu cache**. `implements Serializable` vì có thể nằm
  trong Redis (JSON) và Caffeine.
- `OfferDto` tương tự cho gói cước (có `price` kiểu `BigDecimal` để chính xác tiền tệ).

**Liên kết:** tạo bởi `ProductCatalogService` (map từ entity), lưu/đọc qua `TwoLevelCacheService`,
trả bởi `ProductController`.

---

## C.3. `catalog/model/CatalogChangeEvent.java`

```java
public record CatalogChangeEvent(String entityType, String entityId,
                                 String changeType, long timestamp)
        implements Serializable {}
```
- Khuôn event **nhận từ Kafka**. `entityType` = PRODUCT/OFFER, `changeType` = CREATED/UPDATED/
  DELETED. Phía `management` có bản sao tương đương (cố ý tách rời để giảm ghép nối).

**Liên kết:** deserialize bởi `KafkaConsumerConfig`, xử lý bởi `CatalogChangeConsumer`.

---

## C.4. `catalog/model/PagedResponse.java`

```java
public record PagedResponse<T>(List<T> content, int page, int size,
                               long totalElements, int totalPages) {
    public static <T> PagedResponse<T> from(Page<T> p) {
        return new PagedResponse<>(p.getContent(), p.getNumber(), p.getSize(),
                                   p.getTotalElements(), p.getTotalPages());
    }
}
```
- "Phong bì" phân trang **ổn định** cho đối tác, **tách khỏi** lớp `Page` nội bộ của Spring (để
  đổi thư viện không vỡ hợp đồng API). `from()` chuyển `Page` của Spring → DTO này.

**Liên kết:** dùng bởi `ProductController` khi trả danh sách.

---

## C.5. `catalog/entity/ProductEntity.java` & `OfferEntity.java`

```java
@Entity @Table(name = "product")
public class ProductEntity {
    @Id @Column(name = "id", length = 64) private String id;
    @Column(name = "code", ... nullable = false) private String code;
    ...
    protected ProductEntity() {}     // constructor rỗng cho JPA
    // chỉ có getter -> đối tượng chỉ-đọc phía catalog
}
```
- `@Entity/@Table`: ánh xạ bảng `product` (MariaDB). `@Id`: khóa chính.
- Constructor `protected` rỗng: JPA yêu cầu để dựng đối tượng.
- **Chỉ getter, không setter** → service đọc không vô tình sửa dữ liệu (read model).
- `OfferEntity` ánh xạ bảng `offer`, có `price BigDecimal precision=18 scale=2`.

**Liên kết:** đọc bởi `ProductRepository`/`OfferRepository`, map sang DTO trong
`ProductCatalogService`.

---

## C.6. `catalog/repository/ProductRepository.java` & `OfferRepository.java`

```java
public interface ProductRepository extends JpaRepository<ProductEntity, String> {
    @Query("""
        SELECT p FROM ProductEntity p
        WHERE (:category IS NULL OR p.category = :category)
          AND (:status   IS NULL OR p.status   = :status)
        """)
    Page<ProductEntity> search(@Param("category") String category,
                               @Param("status") String status, Pageable pageable);
}
```
- `JpaRepository`: có sẵn `findById`, `findAll`, phân trang...
- `search`: JPQL lọc **động** — `:param IS NULL OR ...` cho phép bỏ trống bộ lọc (đối tác có thể
  lọc theo category/status hoặc không). `Pageable` đưa page/size/sort.

**Liên kết:** gọi bởi `ProductCatalogService`. `findById` được dùng làm **loader** khi cache miss.

---

## C.7. `catalog/config/CacheConfig.java`

**Mục đích:** Khai báo cache L1 Caffeine cho cơ chế `@Cacheable` của Spring (hiện để dự phòng;
luồng chính dùng `TwoLevelCacheService`).

```java
@Bean
public CacheManager caffeineCacheManager() {
    CaffeineCacheManager manager = new CaffeineCacheManager(L1_PRODUCTS, L1_OFFERS);
    manager.setCaffeine(Caffeine.newBuilder()
        .maximumSize(50_000)
        .expireAfterWrite(Duration.ofSeconds(30))   // TTL ngắn -> L2 là nguồn sự thật
        .recordStats());                            // xuất thống kê cho Micrometer
    manager.setAllowNullValues(true);               // cho phép cache null (chống penetration)
    return manager;
}
```
- `maximumSize`: chặn trên số phần tử (tránh OOM). `expireAfterWrite(30s)`: L1 luôn tươi gần.
- `recordStats()`: cần để xuất hit/miss ra Prometheus.
- `setAllowNullValues(true)`: cho phép lưu marker null.

> Ghi chú trung thực: hit/miss thật của luồng chính nằm trong instance Caffeine **tự quản** ở
> `TwoLevelCacheService`, chưa bind vào MeterRegistry → `cache_gets_total` của bean này hiển thị 0.

---

## C.8. `catalog/config/RedisConfig.java`

**Mục đích:** Cấu hình `RedisTemplate` (L2) với serializer JSON.

```java
@Bean
public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory cf) {
    RedisTemplate<String, Object> template = new RedisTemplate<>();
    template.setConnectionFactory(cf);
    var keySerializer = new StringRedisSerializer();
    var valueSerializer = new GenericJackson2JsonRedisSerializer();   // constructor mặc định
    template.setKeySerializer(keySerializer);
    template.setValueSerializer(valueSerializer);
    ...
    return template;
}
```
- Key dạng chuỗi (dễ đọc/inspect bằng `redis-cli`), value dạng JSON (ngôn ngữ-độc lập).
- Dùng **constructor mặc định** của `GenericJackson2JsonRedisSerializer` (không truyền ObjectMapper
  riêng) — chi tiết kỹ thuật: record là lớp `final` nên không nhúng được `@class`; khi đọc ra sẽ
  thành `Map` và được `TwoLevelCacheService.unwrap` ép kiểu lại qua Jackson.

**Liên kết:** `RedisTemplate` được tiêm vào `TwoLevelCacheService`.

---

## C.9. `catalog/service/TwoLevelCacheService.java` ⭐⭐ (trái tim cache)

**Mục đích:** Điều phối cache-aside qua 2 tầng + 3 cơ chế chống lỗi (avalanche, penetration,
Redis sập).

```java
public static final String NULL_MARKER = "__NULL__";   // dấu hiệu "id này không tồn tại"
private final RedisTemplate<String, Object> redis;
private final ObjectMapper objectMapper;

@Value("${cache.l2.base-ttl-seconds:600}") private long baseTtlSeconds;
@Value("${cache.l2.jitter-seconds:120}")   private long jitterSeconds;
@Value("${cache.l2.null-ttl-seconds:60}")  private long nullTtlSeconds;
```
- `NULL_MARKER`: giá trị quy ước cho "đã tra, không có" → **chống penetration**.
- Các tham số TTL đọc từ cấu hình: TTL nền 600s, jitter 0–120s, TTL cho null 60s.

```java
private final Cache<String, Object> l1 = Caffeine.newBuilder()
        .maximumSize(100_000)
        .expireAfterWrite(Duration.ofSeconds(30))
        .recordStats().build();
```
- **L1 tự quản**: dịch vụ tự nắm instance Caffeine để kiểm soát hoàn toàn luồng L1↔L2 và marker
  null (không phụ thuộc `@Cacheable`).

```java
public <T> T get(String namespace, String key, Class<T> type, Supplier<T> loader) {
    String cacheKey = namespace + ":" + key;          // vd: "product:123"

    Object l1Value = l1.getIfPresent(cacheKey);        // --- L1 ---
    if (l1Value != null) return unwrap(l1Value, type);

    Object l2Value = safeRedisGet(cacheKey);           // --- L2 (Redis), fail-open ---
    if (l2Value != null) { l1.put(cacheKey, l2Value); return unwrap(l2Value, type); }

    T loaded = loader.get();                           // --- loader (DB) ---
    if (loaded == null) {
        l1.put(cacheKey, NULL_MARKER);                 // chống penetration: nhớ "không có"
        safeRedisPut(cacheKey, NULL_MARKER, nullTtlSeconds);
        return null;
    }
    l1.put(cacheKey, loaded);
    safeRedisPut(cacheKey, loaded, randomizedTtl());   // chống avalanche: TTL ngẫu nhiên
    return loaded;
}
```
- **Cache-aside 3 bước**: L1 → L2 → loader(DB). Hit L2 thì **back-fill L1**.
- DB trả null → cache **marker null** ở cả 2 tầng (TTL ngắn) → lần gọi sai sau không chạm DB.
- DB có dữ liệu → ghi cả 2 tầng, L2 dùng **TTL ngẫu nhiên**.

```java
public void evict(String namespace, String key) {
    String cacheKey = namespace + ":" + key;
    l1.invalidate(cacheKey);
    try { redis.delete(cacheKey); }
    catch (RuntimeException e) { log.warn("Redis evict failed ... continuing"); }
}
```
- Xóa 1 key ở **cả L1 và L2**. Đây là hàm `CatalogChangeConsumer` gọi khi nhận event Kafka.
- Redis lỗi cũng **không ném ra ngoài** → không làm hỏng luồng consume.

```java
private <T> T unwrap(Object value, Class<T> type) {
    if (NULL_MARKER.equals(value)) return null;            // "không tồn tại"
    if (type.isInstance(value)) return type.cast(value);
    return objectMapper.convertValue(value, type);          // Map(JSON Redis) -> record
}
```
- Diễn giải giá trị cache: marker → null; đúng kiểu → cast; là `Map` (đọc từ Redis) → ép về
  record bằng Jackson (xử lý vụ record `final` không có `@class`).

```java
private Object safeRedisGet(String key) {
    try { return redis.opsForValue().get(key); }
    catch (RuntimeException e) { log.warn("Redis GET failed (fail-open)"); return null; }
}
private void safeRedisPut(String key, Object value, long ttlSeconds) {
    try { redis.opsForValue().set(key, value, Duration.ofSeconds(ttlSeconds)); }
    catch (RuntimeException e) { log.warn("Redis SET failed (continue L1 only)"); }
}
```
- ⭐ **Fail-open**: mọi thao tác Redis bọc try/catch. Redis chết → coi như cache miss, **degrade
  xuống DB**, hệ thống không sập (demo case tắt Redis).

```java
private long randomizedTtl() {
    long jitter = jitterSeconds <= 0 ? 0 : ThreadLocalRandom.current().nextLong(jitterSeconds + 1);
    return baseTtlSeconds + jitter;       // 600 + (0..120)
}
```
- ⭐ **Chống avalanche**: TTL = nền + ngẫu nhiên → các key populate cùng lúc sẽ **hết hạn rải đều**.

**Liên kết:** dùng bởi `ProductCatalogService` (get) và `CatalogChangeConsumer` (evict).

---

## C.10. `catalog/service/ProductCatalogService.java`

**Mục đích:** Nghiệp vụ đọc: đọc đơn lẻ qua cache, đọc danh sách thẳng DB.

```java
public ProductDto getProduct(String id) {
    return cache.get(NS_PRODUCT, id, ProductDto.class,
        () -> productRepository.findById(id).map(this::toDto).orElse(null));
}
```
- Gọi `TwoLevelCacheService.get` với **loader là lambda** `findById(...).map(toDto)`. Loader chỉ
  chạy khi cache miss. `orElse(null)` → kích hoạt chống penetration nếu không có.

```java
public Page<ProductDto> listProducts(String category, String status, int page, int size) {
    Pageable pageable = PageRequest.of(page, size, Sort.by("code").ascending());
    return productRepository.search(category, status, pageable).map(this::toDto);
}
```
- Danh sách **không cache** (mỗi tổ hợp filter là một truy vấn riêng, ngắn hạn — cache sẽ phình
  bộ nhớ mà hit thấp). Sắp xếp theo `code`.

```java
private ProductDto toDto(ProductEntity e) {
    return new ProductDto(e.getId(), e.getCode(), e.getName(), e.getCategory(),
                          e.getStatus(), e.getDescription(), e.getUpdatedAt());
}
```
- Map entity (model DB) → DTO (model API/cache). Tách 2 lớp model.

**Liên kết:** gọi bởi `ProductController`; gọi `TwoLevelCacheService` + repository.

---

## C.11. `catalog/controller/ProductController.java`

**Mục đích:** Hiện thực các endpoint đọc cho đối tác (theo `openapi.yaml`).

```java
@RestController @RequestMapping("/api/v1") @Tag(name = "products")
public class ProductController {

    @GetMapping("/products")
    public PagedResponse<ProductDto> listProducts(
            @RequestParam(defaultValue="0") @Min(0) int page,
            @RequestParam(defaultValue="20") @Min(1) @Max(200) int size,
            @RequestParam(required=false) String category,
            @RequestParam(required=false) String status) {
        return PagedResponse.from(service.listProducts(category, status, page, size));
    }
```
- `@Min/@Max`: chặn `size` ≤ 200 (đối tác không thể xin trang khổng lồ làm nặng DB).
- Trả `PagedResponse` (phong bì ổn định).

```java
    @GetMapping("/products/{productId}")
    public ResponseEntity<ProductDto> getProduct(@PathVariable String productId) {
        ProductDto product = service.getProduct(productId);
        if (product == null)
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Product not found: " + productId);
        return ResponseEntity.ok()
            .cacheControl(CacheControl.maxAge(Duration.ofSeconds(30)).cachePublic())
            .body(product);
    }
```
- `null` (kể cả từ marker null) → ném **404** (demo penetration trả 404).
- `Cache-Control: max-age=30, public`: gợi ý đối tác **cache phía họ** 30s → giảm tải thêm.
- `/offers` và `/offers/{id}` tương tự cho gói cước.

**Liên kết:** gọi `ProductCatalogService`; lỗi đi qua `GlobalExceptionHandler`.

---

## C.12. `catalog/config/KafkaConsumerConfig.java`

**Mục đích:** Cấu hình consumer Kafka chịu lỗi cho event hủy cache.

```java
props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);   // tự commit thủ công
props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, ErrorHandlingDeserializer.class);
props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ErrorHandlingDeserializer.class);
props.put(ErrorHandlingDeserializer.VALUE_DESERIALIZER_CLASS, JsonDeserializer.class.getName());
props.put(JsonDeserializer.VALUE_DEFAULT_TYPE, CatalogChangeEvent.class.getName());
props.put(JsonDeserializer.TRUSTED_PACKAGES, "com.telecom.bccs.*");
```
- Tắt auto-commit → chỉ commit **sau khi xử lý xong** (an toàn dữ liệu).
- `ErrorHandlingDeserializer` bọc `JsonDeserializer`: payload hỏng → đẩy sang error handler, **không
  làm chết** listener. Cố định kiểu đích = `CatalogChangeEvent`, chỉ tin package nội bộ (an ninh).

```java
factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL_IMMEDIATE);
DefaultErrorHandler errorHandler = new DefaultErrorHandler(new FixedBackOff(1000L, 3L));
factory.setCommonErrorHandler(errorHandler);
```
- Ack thủ công ngay khi xử lý xong. Lỗi → **thử lại 3 lần cách 1s** rồi bỏ qua (không kẹt phân
  vùng vì 1 message độc).

**Liên kết:** factory `kafkaListenerContainerFactory` được `CatalogChangeConsumer` tham chiếu.

---

## C.13. `catalog/kafka/CatalogChangeConsumer.java` ⭐

**Mục đích:** Nghe topic `catalog.changes`, hủy cache đúng key; khôi phục traceId từ Kafka header.

```java
@KafkaListener(topics = "${app.kafka.topic.catalog-changes:catalog.changes}",
               containerFactory = "kafkaListenerContainerFactory")
public void onCatalogChange(ConsumerRecord<String, CatalogChangeEvent> record, Acknowledgment ack) {
    restoreTraceContext(record);                 // đưa traceId/clientId vào MDC để log nối được
    try {
        CatalogChangeEvent event = record.value();
        if (event == null) { ack.acknowledge(); return; }
        String namespace = switch (event.entityType()) {
            case "PRODUCT" -> "product"; case "OFFER" -> "offer"; default -> null; };
        if (namespace != null) {
            cache.evict(namespace, event.entityId());
            log.info("Evicted cache {}:{} due to {} event", ...);
        }
        ack.acknowledge();                       // commit chỉ khi xử lý thành công
    } finally { MDC.clear(); }
}
```
- Nhận event → ánh xạ `entityType` sang namespace cache → gọi `TwoLevelCacheService.evict`.
- `ack.acknowledge()` đặt **sau** xử lý → đảm bảo không mất việc nếu crash giữa chừng.
- Đây là mắt xích biến "ghi" thành "hủy cache" (demo case invalidation).

```java
private void restoreTraceContext(ConsumerRecord<...> record) {
    var traceHeader  = record.headers().lastHeader(MdcConstants.KAFKA_HEADER_TRACE_ID);
    var clientHeader = record.headers().lastHeader(MdcConstants.KAFKA_HEADER_CLIENT_ID);
    if (traceHeader != null)  MDC.put(MdcConstants.MDC_TRACE_ID, new String(traceHeader.value(), UTF_8));
    if (clientHeader != null) MDC.put(MdcConstants.MDC_CLIENT_ID, new String(clientHeader.value(), UTF_8));
    MDC.put(MdcConstants.MDC_SERVICE_NAME, "product-catalog-service");
}
```
- ⭐ Lấy traceId/clientId từ **header Kafka** (do `CatalogEventPublisher` gắn) → log dòng "Evicted
  cache" **nối được** với request ghi gốc trên Kibana (demo: traceId trong log evict).

**Liên kết:** dùng `TwoLevelCacheService`, `MdcConstants`; nhận từ `CatalogEventPublisher` (management).

---

## C.14. `catalog/web/TraceContextFilter.java`

**Mục đích:** Ở mỗi service servlet, nạp MDC từ header gateway tiêm vào → mọi log có traceId.

```java
@Component @Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceContextFilter extends OncePerRequestFilter {
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain) {
        try {
            String traceId = header(req, MdcConstants.HEADER_TRACE_ID, TraceContext.newTraceId());
            MDC.put(MdcConstants.MDC_TRACE_ID, traceId);
            MDC.put(MdcConstants.MDC_SPAN_ID, header(req, HEADER_SPAN_ID, TraceContext.newSpanId()));
            MDC.put(MdcConstants.MDC_CLIENT_ID, header(req, HEADER_CLIENT_ID, "unknown"));
            MDC.put(MdcConstants.MDC_USER_ID,  header(req, HEADER_USER_ID, "unknown"));
            MDC.put(MdcConstants.MDC_SERVICE_NAME, "product-catalog-service");
            res.setHeader(MdcConstants.HEADER_TRACE_ID, traceId);
            chain.doFilter(req, res);
        } finally { MDC.clear(); }     // dọn MDC để không rò sang request khác
    }
}
```
- `OncePerRequestFilter`: đảm bảo chạy **đúng 1 lần/request**. `@Order(HIGHEST_PRECEDENCE)`: chạy
  sớm nhất.
- Lấy traceId từ `X-Trace-Id` (gateway tiêm); nếu thiếu (gọi nội bộ trực tiếp) → tự sinh.
- `MDC.clear()` trong `finally` để tránh rò ngữ cảnh giữa các request dùng chung thread pool.

> 3 service servlet (catalog, management, integration) có filter **giống hệt nhau** chỉ khác
> `serviceName`. Mục đích như nhau.

---

## C.15. `catalog/web/GlobalExceptionHandler.java`

**Mục đích:** Bắt mọi lỗi, trả `ApiError` JSON thống nhất kèm traceId.

```java
@RestControllerAdvice
public class GlobalExceptionHandler {
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ApiError> handleStatus(ResponseStatusException ex, HttpServletRequest req) {
        HttpStatus status = HttpStatus.valueOf(ex.getStatusCode().value());
        return ResponseEntity.status(status).body(
            ApiError.of(status.value(), status.getReasonPhrase(), ex.getReason(), req.getRequestURI(), traceId()));
    }
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleGeneric(Exception ex, HttpServletRequest req) {
        return ResponseEntity.status(500).body(ApiError.of(500, "Internal Server Error", ex.getMessage(), ...));
    }
    private String traceId() { String t = MDC.get(MdcConstants.MDC_TRACE_ID); return t == null ? "unknown" : t; }
}
```
- `@RestControllerAdvice`: áp cho mọi controller. `ResponseStatusException` (vd 404 từ controller)
  → trả đúng status. Lỗi khác → 500. Luôn kèm `traceId` lấy từ MDC.

**Liên kết:** xử lý lỗi từ `ProductController`; dùng `ApiError`.

---

## C.16. `catalog/config/OpenApiConfig.java`

```java
@Bean
public OpenAPI catalogOpenApi() {
    return new OpenAPI().info(new Info().title("BCCS Product Catalog API").version("4.0.0")...);
}
```
- Khai báo metadata cho Swagger UI (`/swagger-ui.html`) và `/v3/api-docs`. Phục vụ đối tác tra cứu hợp đồng API.

---

# PHẦN D — product-management-service (GHI/CRUD)

## D.1. `management/ProductManagementApplication.java`
Như catalog nhưng không cần `@EnableKafka` (chỉ **gửi** event, không nghe). `@SpringBootApplication` đủ.

## D.2. `management/entity/ProductEntity.java`

Khác bản catalog ở chỗ **có setter** (vì đây là write model) và:

```java
@PrePersist @PreUpdate
void touch() { this.updatedAt = Instant.now(); }
```
- Hook JPA: tự cập nhật `updatedAt` mỗi khi insert/update → audit thời điểm sửa.

```java
public ProductEntity(String id, String code, String name, String category, String status, String description) {...}
```
- Constructor đầy đủ để service tạo mới.

## D.3. `management/model/ProductRequest.java`

```java
public record ProductRequest(
    @NotBlank String code,
    @NotBlank String name,
    String category,
    @NotBlank @Pattern(regexp = "ACTIVE|INACTIVE|RETIRED") String status,
    String description) {}
```
- DTO **đầu vào** + ràng buộc kiểm tra: `@NotBlank` (bắt buộc), `@Pattern` (status chỉ nhận 3 giá
  trị). Vi phạm → `MethodArgumentNotValidException` → 400 (demo case validation).

## D.4. `management/model/ProductResponse.java`

```java
public static ProductResponse from(ProductEntity e) { return new ProductResponse(e.getId(), ...); }
```
- DTO **đầu ra**, map từ entity. Tách model API khỏi entity DB.

## D.5. `management/repository/ProductRepository.java`

```java
public interface ProductRepository extends JpaRepository<ProductEntity, String> {
    boolean existsByCode(String code);
}
```
- `existsByCode`: Spring Data tự sinh truy vấn — phục vụ kiểm tra trùng code (→ 409).

## D.6. `management/config/KafkaProducerConfig.java` ⭐ (độ bền event)

```java
props.put(ProducerConfig.ACKS_CONFIG, "all");                       // chờ mọi replica xác nhận
props.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);          // không trùng/không mất
props.put(ProducerConfig.RETRIES_CONFIG, 5);
props.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 12000);
props.put(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION, 5);
```
- `acks=all` + idempotence: đảm bảo event **không mất, không nhân đôi** (kết hợp retry). Đây là
  cấu hình "độ bền" cho luồng đồng bộ cache.

**Liên kết:** `KafkaTemplate` được `CatalogEventPublisher` dùng.

## D.7. `management/kafka/CatalogEventPublisher.java` ⭐

```java
public void publish(CatalogChangeEvent event) {
    ProducerRecord<String, CatalogChangeEvent> record =
        new ProducerRecord<>(topic, event.entityId(), event);        // key = entityId
    addHeader(record, KAFKA_HEADER_TRACE_ID,  MDC.get(MDC_TRACE_ID));  // mang traceId qua Kafka
    addHeader(record, KAFKA_HEADER_CLIENT_ID, MDC.get(MDC_CLIENT_ID));
    kafkaTemplate.send(record).whenComplete((result, ex) -> {
        if (ex != null) log.error("Failed to publish ...");          // chỉ log; TTL vẫn bao staleness
        else log.info("Published {} {} event ...");
    });
}
```
- Key = `entityId` → cùng sản phẩm vào cùng phân vùng (giữ thứ tự event).
- ⭐ Gắn **traceId/clientId vào header Kafka** (từ MDC) → để consumer khôi phục, nối log xuyên hệ thống.
- Gửi bất đồng bộ; thất bại chỉ log (TTL cache là lưới an toàn cuối).

**Liên kết:** gọi bởi `ProductManagementService.publishAfterCommit`; tới `CatalogChangeConsumer`.

## D.8. `management/service/ProductManagementService.java` ⭐ (event sau commit)

```java
@Transactional
public ProductResponse create(ProductRequest req) {
    if (repository.existsByCode(req.code()))
        throw new ResponseStatusException(HttpStatus.CONFLICT, "Product code already exists: " + req.code());
    String id = UUID.randomUUID().toString();
    ProductEntity entity = new ProductEntity(id, req.code(), req.name(), req.category(), req.status(), req.description());
    repository.save(entity);
    publishAfterCommit("CREATED", id);
    return ProductResponse.from(entity);
}
```
- `@Transactional`: toàn bộ trong 1 giao dịch DB.
- Trùng code → **409** (demo conflict). Sinh id = UUID. Lưu rồi đăng ký bắn event.
- `update`/`delete` tương tự: không thấy → **404**; sau đó `publishAfterCommit("UPDATED"/"DELETED", id)`.

```java
private void publishAfterCommit(String changeType, String id) {
    CatalogChangeEvent event = new CatalogChangeEvent("PRODUCT", id, changeType, System.currentTimeMillis());
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() { eventPublisher.publish(event); }
        });
    } else { eventPublisher.publish(event); }
}
```
- ⭐⭐ **Điểm tinh tế:** event chỉ bắn trong `afterCommit()` — tức **sau khi giao dịch DB commit
  thành công**. Nếu rollback → không bắn → catalog không hủy cache cho thay đổi không tồn tại.
  Đây là chi tiết hội đồng đánh giá cao về tính đúng đắn.

**Liên kết:** gọi bởi `ProductManagementController`; dùng repository + `CatalogEventPublisher`.

## D.9. `management/controller/ProductManagementController.java`

```java
@PostMapping
public ResponseEntity<ProductResponse> create(@Valid @RequestBody ProductRequest req) {
    return ResponseEntity.status(HttpStatus.CREATED).body(service.create(req));   // 201
}
@PutMapping("/{id}")  public ProductResponse update(@PathVariable String id, @Valid @RequestBody ProductRequest req) {...}
@DeleteMapping("/{id}") public ResponseEntity<Void> delete(@PathVariable String id) { service.delete(id); return ResponseEntity.noContent().build(); } // 204
```
- `@Valid`: kích hoạt kiểm tra ràng buộc trên `ProductRequest` (→ 400 nếu sai).
- CREATE → 201, DELETE → 204, đúng chuẩn REST.

## D.10. `management/web/TraceContextFilter.java` & `GlobalExceptionHandler.java`
Giống bản catalog (mục C.14, C.15), khác `serviceName="product-management-service"`. `GlobalExceptionHandler`
ở management có thêm nhánh:

```java
@ExceptionHandler(MethodArgumentNotValidException.class)
public ResponseEntity<ApiError> handleValidation(MethodArgumentNotValidException ex, HttpServletRequest req) {
    String msg = ex.getBindingResult().getFieldErrors().stream()
        .map(e -> e.getField() + ": " + e.getDefaultMessage()).collect(Collectors.joining("; "));
    return ResponseEntity.badRequest().body(ApiError.of(400, "Bad Request", msg, req.getRequestURI(), traceId()));
}
```
- Gom lỗi từng field thành một thông báo → trả **400** rõ ràng (demo case validation).

---

# PHẦN E — integration-service (xác thực bên thứ 3)

## E.1. `integration/IntegrationApplication.java`
`@SpringBootApplication` chuẩn.

## E.2. `integration/model/TokenValidationResult.java`

```java
public record TokenValidationResult(boolean active, String sub, String client_id, Long exp, String error) {
    public static TokenValidationResult inactive(String error) { return new TokenValidationResult(false, null, null, null, error); }
    public static TokenValidationResult active(String sub, String clientId, Long exp) { return new TokenValidationResult(true, sub, clientId, exp, null); }
}
```
- Theo kiểu **OAuth2 introspection**: `active` quyết định hợp lệ hay không. Hai factory cho 2 nhánh.
- Tên trường `client_id` (snake_case) để khớp chuẩn introspection mà gateway đọc.

## E.3. `integration/client/ThirdPartyAuthClient.java` ⭐ (circuit breaker)

```java
@CircuitBreaker(name = "thirdPartyAuth", fallbackMethod = "fallback")
@Retry(name = "thirdPartyAuth")
public TokenValidationResult introspect(String token) {
    Map<String, Object> body = restClient.post()
        .header(CONTENT_TYPE, "application/x-www-form-urlencoded")
        .body("token=" + token).retrieve().body(Map.class);
    if (body == null || !Boolean.TRUE.equals(body.get("active")))
        return TokenValidationResult.inactive("inactive_token");
    Long exp = body.get("exp") instanceof Number n ? n.longValue() : null;
    return TokenValidationResult.active(String.valueOf(body.getOrDefault("sub","unknown")),
                                        String.valueOf(body.getOrDefault("client_id","unknown")), exp);
}
private TokenValidationResult fallback(String token, Throwable t) {
    log.warn("3rd-party auth introspection unavailable ...");
    return TokenValidationResult.inactive("auth_provider_unavailable");
}
```
- ⭐ `@CircuitBreaker` + `@Retry` (Resilience4j, cần `spring-boot-starter-aop`): gọi cổng auth bên
  thứ 3. Khi nó chậm/sập, breaker mở → `fallback` trả `inactive` ngay, **không treo, không sập dây
  chuyền**. Đây là điểm cô lập rủi ro bên ngoài.

**Liên kết:** gọi bởi `TokenValidationService`; cấu hình breaker/retry trong `application.yml`.

## E.4. `integration/service/TokenValidationService.java`

```java
@PostConstruct void init() { ... dựng publicKey từ integration.auth.jwt-public-key ... }

public TokenValidationResult validate(String token) {
    if (publicKey != null) {
        try {
            Claims claims = Jwts.parser().verifyWith(publicKey).clockSkewSeconds(30)
                                .build().parseSignedClaims(token).getPayload();
            ... return TokenValidationResult.active(claims.getSubject(), clientId, exp);
        } catch (JwtException e) { /* rơi xuống introspection */ }
    }
    return thirdPartyAuthClient.introspect(token);
}
```
- Ưu tiên **verify cục bộ** bằng public key (nhanh); token không verify được cục bộ (vd token
  tham chiếu/opaque) → **introspection từ xa**. Đây là logic "điều phối xác thực" của service tích hợp.

**Liên kết:** gọi bởi `AuthValidationController`; dùng `ThirdPartyAuthClient`.

## E.5. `integration/controller/AuthValidationController.java`

```java
@PostMapping("/validate")
public ResponseEntity<TokenValidationResult> validate(
        @RequestHeader(value = AUTHORIZATION, required = false) String authHeader) {
    if (authHeader == null || !authHeader.startsWith("Bearer "))
        return ResponseEntity.ok(TokenValidationResult.inactive("missing_bearer_token"));
    String token = authHeader.substring(7);
    return ResponseEntity.ok(validationService.validate(token));
}
```
- Endpoint mà **gateway gọi tới** khi xác thực từ xa (introspection fallback). Thiếu token →
  `active=false` (demo case). Có token → trả kết quả validate.

**Liên kết:** được gọi bởi `AuthenticationFilter` (gateway, hàm `validateRemotely`).

## E.6. `integration/web/TraceContextFilter.java`
Giống mục C.14, `serviceName="integration-service"` (không có `userId` vì service này không cần).

---

# PHẦN F — BẢNG LIÊN KẾT CHÉO (ai gọi ai)

| Thành phần | Gọi tới / Phụ thuộc | Được gọi bởi / Kích hoạt bởi |
|---|---|---|
| `TraceContextGlobalFilter` | `TraceContext`, `MdcConstants` | Mọi request vào gateway (chạy đầu) |
| `AuthenticationFilter` | `JwtPublicKeyProvider`, `CircuitBreakerRegistry`, `integration-service`, `ApiError` | Mọi request (sau trace filter) |
| `RateLimiterConfig.clientIdKeyResolver` | `MdcConstants` (X-Client-Id) | `application.yml` RequestRateLimiter |
| `FallbackController` | `ApiError` | Filter CircuitBreaker khi route lỗi |
| `ProductController` | `ProductCatalogService`, `PagedResponse` | Đối tác (qua gateway) |
| `ProductCatalogService` | `TwoLevelCacheService`, `ProductRepository`, `OfferRepository` | `ProductController` |
| `TwoLevelCacheService` | `RedisTemplate`, `ObjectMapper`, loader (repo) | `ProductCatalogService`, `CatalogChangeConsumer` |
| `CatalogChangeConsumer` | `TwoLevelCacheService`, `MdcConstants` | Kafka topic `catalog.changes` |
| `ProductManagementController` | `ProductManagementService` | Đối tác (qua gateway) |
| `ProductManagementService` | `ProductRepository`, `CatalogEventPublisher` | `ProductManagementController` |
| `CatalogEventPublisher` | `KafkaTemplate`, `MdcConstants` | `ProductManagementService.afterCommit` |
| `TokenValidationService` | `ThirdPartyAuthClient`, public key | `AuthValidationController` |
| `ThirdPartyAuthClient` | RestClient + Resilience4j | `TokenValidationService` |
| `AuthValidationController` | `TokenValidationService` | `AuthenticationFilter` (gateway) |
| `common-lib` (3 lớp) | — | **Cả 4 service** |

---

## Tổng kết các "điểm vàng" để bảo vệ trước hội đồng
1. **`TwoLevelCacheService.get/evict`** — 3 cơ chế chống lỗi cache trong 1 file (avalanche/penetration/fail-open).
2. **`ProductManagementService.publishAfterCommit`** — bắn event sau commit (đúng đắn nhất quán).
3. **`CatalogEventPublisher` + `CatalogChangeConsumer`** — traceId xuyên Kafka, hủy cache đúng key.
4. **`TraceContextGlobalFilter` + `AuthenticationFilter`** — W3C trace + xác thực bất đối xứng in-memory + circuit breaker.
