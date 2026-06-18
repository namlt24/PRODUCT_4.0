package com.telecom.bccs.gateway.filter;

import com.telecom.bccs.common.tracing.MdcConstants;
import com.telecom.bccs.common.tracing.TraceContext;
import org.slf4j.MDC;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * First filter in the chain. Reconstructs the W3C trace context from an incoming
 * {@code traceparent} header (coming from a 3rd-party system) or generates a fresh
 * UUID-v4-based trace id when missing. It then:
 *   - injects X-Trace-Id / X-Span-Id / traceparent into the downstream request, and
 *   - populates the MDC so the gateway's own JSON logs carry traceId/spanId.
 */
@Component
public class TraceContextGlobalFilter implements GlobalFilter, Ordered {

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();

        String traceparent = request.getHeaders().getFirst(MdcConstants.W3C_TRACEPARENT);
        String traceId = TraceContext.extractTraceId(traceparent);
        String spanId = TraceContext.extractSpanId(traceparent);

        if (traceId == null) {
            traceId = TraceContext.newTraceId();
        }
        // Gateway always starts a new span for the downstream hop
        spanId = TraceContext.newSpanId();
        String rebuiltTraceparent = TraceContext.buildTraceparent(traceId, spanId);

        ServerHttpRequest mutated = request.mutate()
                .header(MdcConstants.HEADER_TRACE_ID, traceId)
                .header(MdcConstants.HEADER_SPAN_ID, spanId)
                .header(MdcConstants.W3C_TRACEPARENT, rebuiltTraceparent)
                .build();

        // Store for response + downstream observability
        exchange.getAttributes().put(MdcConstants.MDC_TRACE_ID, traceId);

        final String fTraceId = traceId;
        final String fSpanId = spanId;
        MDC.put(MdcConstants.MDC_TRACE_ID, fTraceId);
        MDC.put(MdcConstants.MDC_SPAN_ID, fSpanId);
        MDC.put(MdcConstants.MDC_SERVICE_NAME, "api-gateway");

        return chain.filter(exchange.mutate().request(mutated).build())
                .doFinally(signal -> {
                    exchange.getResponse().getHeaders()
                            .add(MdcConstants.HEADER_TRACE_ID, fTraceId);
                    MDC.clear();
                });
    }

    @Override
    public int getOrder() {
        // Must run before everything else so all logs are correlated
        return Ordered.HIGHEST_PRECEDENCE;
    }
}
