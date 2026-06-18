package com.telecom.bccs.gateway.config;

import com.telecom.bccs.common.tracing.MdcConstants;
import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import reactor.core.publisher.Mono;

/**
 * Rate-limiting key strategy. Each 3rd-party partner is identified by its X-Client-Id
 * (resolved from the validated JWT). Limiting per client prevents one partner's bulk
 * scan from exhausting capacity for everyone else (noisy-neighbour protection).
 */
@Configuration
public class RateLimiterConfig {

    @Bean
    public KeyResolver clientIdKeyResolver() {
        return exchange -> {
            String clientId = exchange.getRequest().getHeaders()
                    .getFirst(MdcConstants.HEADER_CLIENT_ID);
            if (clientId == null || clientId.isBlank()) {
                // Fall back to remote address so unauthenticated probes still get limited
                var remote = exchange.getRequest().getRemoteAddress();
                clientId = remote != null ? remote.getAddress().getHostAddress() : "anonymous";
            }
            return Mono.just(clientId);
        };
    }
}
