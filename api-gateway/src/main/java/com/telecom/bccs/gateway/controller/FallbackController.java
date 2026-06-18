package com.telecom.bccs.gateway.controller;

import com.telecom.bccs.common.web.dto.ApiError;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Circuit-breaker fallback endpoints. When a downstream service (or the partner auth
 * provider) is open/unavailable, the gateway returns a controlled 503 instead of hanging
 * or cascading the failure. The system degrades gracefully rather than going down.
 */
@RestController
@RequestMapping("/fallback")
public class FallbackController {

    @RequestMapping(value = "/catalog", method = {RequestMethod.GET, RequestMethod.POST})
    public Mono<ResponseEntity<ApiError>> catalog(ServerWebExchange exchange) {
        return build(exchange, "product-catalog-service temporarily unavailable");
    }

    @RequestMapping(value = "/management", method = {RequestMethod.GET, RequestMethod.POST})
    public Mono<ResponseEntity<ApiError>> management(ServerWebExchange exchange) {
        return build(exchange, "product-management-service temporarily unavailable");
    }

    @RequestMapping(value = "/integration", method = {RequestMethod.GET, RequestMethod.POST})
    public Mono<ResponseEntity<ApiError>> integration(ServerWebExchange exchange) {
        return build(exchange, "integration-service temporarily unavailable");
    }

    private Mono<ResponseEntity<ApiError>> build(ServerWebExchange exchange, String message) {
        String traceId = exchange.getResponse().getHeaders().getFirst("X-Trace-Id");
        ApiError error = ApiError.of(503, "Service Unavailable", message,
                exchange.getRequest().getURI().getPath(), traceId == null ? "unknown" : traceId);
        return Mono.just(ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header("Retry-After", "5")
                .body(error));
    }
}
