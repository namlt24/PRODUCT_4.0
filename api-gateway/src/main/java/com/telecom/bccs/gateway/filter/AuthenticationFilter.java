package com.telecom.bccs.gateway.filter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.telecom.bccs.common.tracing.MdcConstants;
import com.telecom.bccs.common.web.dto.ApiError;
import com.telecom.bccs.gateway.config.JwtPublicKeyProvider;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.reactor.circuitbreaker.operator.CircuitBreakerOperator;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Authenticates every non-public request before it is routed.
 *
 * <p>Strategy:
 * <ol>
 *   <li>Bypass configured permit-all paths (health, metrics, fallbacks).</li>
 *   <li>Require a Bearer token; reject 401 if absent.</li>
 *   <li>Validate the token in-memory against the RSA public key (fast path,
 *       sub-ms, no network call).</li>
 *   <li>If no local key is configured, fall back to the 3rd-party introspection
 *       endpoint, wrapped in a Resilience4j circuit breaker to avoid cascading
 *       failure when the partner auth provider is degraded.</li>
 *   <li>On success, inject X-User-Id / X-Client-Id context headers and continue.</li>
 * </ol>
 */
@Component
public class AuthenticationFilter implements GlobalFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(AuthenticationFilter.class);
    private static final AntPathMatcher PATH_MATCHER = new AntPathMatcher();

    private final JwtPublicKeyProvider keyProvider;
    private final CircuitBreakerRegistry circuitBreakerRegistry;
    private final ObjectMapper objectMapper;
    private final WebClient introspectionClient;
    private final List<String> permitAll;

    public AuthenticationFilter(JwtPublicKeyProvider keyProvider,
                                CircuitBreakerRegistry circuitBreakerRegistry,
                                ObjectMapper objectMapper,
                                WebClient.Builder webClientBuilder,
                                @Value("${gateway.auth.introspection-uri}") String introspectionUri,
                                @Value("${gateway.auth.permit-all}") List<String> permitAll) {
        this.keyProvider = keyProvider;
        this.circuitBreakerRegistry = circuitBreakerRegistry;
        this.objectMapper = objectMapper;
        this.introspectionClient = webClientBuilder.baseUrl(introspectionUri).build();
        this.permitAll = permitAll;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        String path = request.getURI().getPath();

        if (isPermitAll(path)) {
            return chain.filter(exchange);
        }

        String authHeader = request.getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            return unauthorized(exchange, "Missing or malformed Authorization header");
        }
        String token = authHeader.substring(7);

        Mono<AuthResult> validation = keyProvider.isLocalValidationEnabled()
                ? validateLocally(token)
                : validateRemotely(token);

        return validation
                .flatMap(result -> {
                    ServerHttpRequest mutated = request.mutate()
                            .header(MdcConstants.HEADER_USER_ID, result.userId())
                            .header(MdcConstants.HEADER_CLIENT_ID, result.clientId())
                            .build();
                    return chain.filter(exchange.mutate().request(mutated).build());
                })
                .onErrorResume(ex -> {
                    log.warn("Authentication failed for path {}: {}", path, ex.getMessage());
                    return unauthorized(exchange, "Invalid or expired token");
                });
    }

    /** Fast in-memory asymmetric validation using the cached RSA public key. */
    private Mono<AuthResult> validateLocally(String token) {
        return Mono.fromCallable(() -> {
            Claims claims = Jwts.parser()
                    .verifyWith(keyProvider.getPublicKey())
                    .clockSkewSeconds(30)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
            String userId = claims.getSubject();
            String clientId = claims.get("client_id", String.class);
            if (clientId == null) {
                clientId = claims.get("azp", String.class); // OAuth2 authorized party
            }
            return new AuthResult(userId != null ? userId : "unknown",
                    clientId != null ? clientId : "unknown");
        });
    }

    /** Remote introspection fallback, protected by a circuit breaker + timeout. */
    private Mono<AuthResult> validateRemotely(String token) {
        var circuitBreaker = circuitBreakerRegistry.circuitBreaker("authProvider");
        return introspectionClient.post()
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .retrieve()
                .bodyToMono(Map.class)
                .timeout(Duration.ofSeconds(2))
                .transformDeferred(CircuitBreakerOperator.of(circuitBreaker))
                .handle((body, sink) -> {
                    Object active = body.get("active");
                    if (Boolean.TRUE.equals(active)) {
                        sink.next(new AuthResult(
                                String.valueOf(body.getOrDefault("sub", "unknown")),
                                String.valueOf(body.getOrDefault("client_id", "unknown"))));
                    } else {
                        sink.error(new IllegalStateException("Token not active"));
                    }
                });
    }

    private boolean isPermitAll(String path) {
        return permitAll.stream().anyMatch(p -> PATH_MATCHER.match(p, path));
    }

    private Mono<Void> unauthorized(ServerWebExchange exchange, String message) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(HttpStatus.UNAUTHORIZED);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);

        String traceId = (String) exchange.getAttributes()
                .getOrDefault(MdcConstants.MDC_TRACE_ID, "unknown");
        ApiError error = ApiError.of(401, "Unauthorized", message,
                exchange.getRequest().getURI().getPath(), traceId);
        try {
            byte[] bytes = objectMapper.writeValueAsBytes(error);
            DataBuffer buffer = response.bufferFactory().wrap(bytes);
            return response.writeWith(Mono.just(buffer));
        } catch (Exception e) {
            return response.setComplete();
        }
    }

    @Override
    public int getOrder() {
        // After trace context filter, before routing
        return Ordered.HIGHEST_PRECEDENCE + 10;
    }

    private record AuthResult(String userId, String clientId) {
    }
}
