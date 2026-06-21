package com.telecom.bccs.gateway.filter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.telecom.bccs.common.tracing.MdcConstants;
import com.telecom.bccs.common.web.dto.ApiError;
import com.telecom.bccs.gateway.ratelimit.PartnerRateLimitConfigService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * Xác thực ĐỐI TÁC bằng cặp (X-Client-Id, X-Api-Key) ngay tại gateway — KHÔNG gọi
 * integration-service. Thay thế cho việc xác thực JWT trước đây.
 *
 * <p>Luồng:
 * <ol>
 *   <li>Bỏ qua các path permit-all (health, metrics, fallback).</li>
 *   <li>Yêu cầu đủ {@code X-Client-Id} + {@code X-Api-Key}; thiếu → 401.</li>
 *   <li>{@link PartnerRateLimitConfigService#authenticate} so khớp SHA-256(API_KEY) với hash đã
 *       nạp sẵn trong bộ nhớ (so sánh hằng thời gian). Sai/đối tác treo → 401.</li>
 *   <li>Thành công: đặt lại {@code X-Client-Id} = đối tác đã xác thực (để rate-limit + trace dùng),
 *       và XÓA {@code X-Api-Key} để bí mật không rò xuống downstream.</li>
 * </ol>
 *
 * <p>Vì api_key đã chứng minh quyền sở hữu client_id, {@code X-Client-Id} sau bước này là tin cậy
 * và {@code partnerKeyResolver} dùng nó làm khóa rate-limit không sợ giả mạo.
 */
@Component
public class ApiKeyAuthFilter implements GlobalFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(ApiKeyAuthFilter.class);
    private static final AntPathMatcher PATH_MATCHER = new AntPathMatcher();
    /** Header chứa API key thô do đối tác gửi. */
    public static final String HEADER_API_KEY = "X-Api-Key";

    private final PartnerRateLimitConfigService partnerRegistry;
    private final ObjectMapper objectMapper;
    private final List<String> permitAll;

    public ApiKeyAuthFilter(PartnerRateLimitConfigService partnerRegistry,
                            ObjectMapper objectMapper,
                            @Value("${gateway.auth.permit-all}") List<String> permitAll) {
        this.partnerRegistry = partnerRegistry;
        this.objectMapper = objectMapper;
        this.permitAll = permitAll;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        String path = request.getURI().getPath();

        if (isPermitAll(path)) {
            return chain.filter(exchange);
        }

        String clientId = request.getHeaders().getFirst(MdcConstants.HEADER_CLIENT_ID);
        String apiKey = request.getHeaders().getFirst(HEADER_API_KEY);
        if (clientId == null || clientId.isBlank() || apiKey == null || apiKey.isBlank()) {
            return unauthorized(exchange, "Missing X-Client-Id or X-Api-Key");
        }

        if (!partnerRegistry.authenticate(clientId, apiKey)) {
            log.warn("API key auth failed for client {} on path {}", clientId, path);
            return unauthorized(exchange, "Invalid client id or API key");
        }

        // Đối tác hợp lệ: chuẩn hóa X-Client-Id (tin cậy) và loại bỏ secret khỏi request downstream
        ServerHttpRequest mutated = request.mutate()
                .header(MdcConstants.HEADER_CLIENT_ID, clientId)
                .headers(h -> h.remove(HEADER_API_KEY))
                .build();
        return chain.filter(exchange.mutate().request(mutated).build());
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
        // Sau TraceContextGlobalFilter (để có traceId trong log/lỗi), trước định tuyến
        return Ordered.HIGHEST_PRECEDENCE + 10;
    }
}
