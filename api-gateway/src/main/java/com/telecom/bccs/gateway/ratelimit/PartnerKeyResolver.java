package com.telecom.bccs.gateway.ratelimit;

import com.telecom.bccs.common.tracing.MdcConstants;
import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * BƯỚC 1 — Custom KeyResolver nhận diện ĐỐI TÁC.
 *
 * <p>Khóa rate-limit = mã đối tác (clientId) lấy từ header {@code X-Client-Id} mà
 * {@code AuthenticationFilter} đã trích từ JWT đã xác thực (claim {@code client_id}/{@code azp}).
 * Vì lấy từ token đã ký, đối tác KHÔNG thể giả mạo clientId để "mượn" hạn mức của bên khác.
 *
 * <p>Mỗi đối tác có một "xô token" riêng → một đối tác quét ồ ạt không ảnh hưởng hạn mức của
 * đối tác khác. Nếu request chưa xác thực (chưa có clientId) thì rơi về địa chỉ IP để vẫn chặn.
 */
@Component("partnerKeyResolver")
public class PartnerKeyResolver implements KeyResolver {

    public static final String ANONYMOUS = "anonymous";

    @Override
    public Mono<String> resolve(org.springframework.web.server.ServerWebExchange exchange) {
        String clientId = exchange.getRequest().getHeaders().getFirst(MdcConstants.HEADER_CLIENT_ID);
        if (clientId != null && !clientId.isBlank()) {
            return Mono.just(clientId);
        }
        var remote = exchange.getRequest().getRemoteAddress();
        String ip = remote != null ? remote.getAddress().getHostAddress() : ANONYMOUS;
        return Mono.just("ip:" + ip);
    }
}
