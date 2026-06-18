package com.telecom.bccs.integration.client;

import com.telecom.bccs.integration.model.TokenValidationResult;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Map;

/**
 * Calls the 3rd-party auth provider's introspection endpoint. The call is wrapped with a
 * Resilience4j circuit breaker + retry: if the partner provider is down or slow, the breaker
 * opens and {@link #fallback} returns an inactive result instead of cascading the failure.
 */
@Component
public class ThirdPartyAuthClient {

    private static final Logger log = LoggerFactory.getLogger(ThirdPartyAuthClient.class);

    private final RestClient restClient;

    public ThirdPartyAuthClient(RestClient.Builder builder,
                                @Value("${integration.auth.introspection-url}") String introspectionUrl) {
        this.restClient = builder.baseUrl(introspectionUrl).build();
    }

    @CircuitBreaker(name = "thirdPartyAuth", fallbackMethod = "fallback")
    @Retry(name = "thirdPartyAuth")
    @SuppressWarnings("unchecked")
    public TokenValidationResult introspect(String token) {
        Map<String, Object> body = restClient.post()
                .header(HttpHeaders.CONTENT_TYPE, "application/x-www-form-urlencoded")
                .body("token=" + token)
                .retrieve()
                .body(Map.class);

        if (body == null || !Boolean.TRUE.equals(body.get("active"))) {
            return TokenValidationResult.inactive("inactive_token");
        }
        Long exp = body.get("exp") instanceof Number n ? n.longValue() : null;
        return TokenValidationResult.active(
                String.valueOf(body.getOrDefault("sub", "unknown")),
                String.valueOf(body.getOrDefault("client_id", "unknown")),
                exp);
    }

    @SuppressWarnings("unused")
    private TokenValidationResult fallback(String token, Throwable t) {
        log.warn("3rd-party auth introspection unavailable, returning inactive: {}", t.getMessage());
        return TokenValidationResult.inactive("auth_provider_unavailable");
    }
}
