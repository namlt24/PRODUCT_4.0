package com.telecom.bccs.catalog.web;

import com.telecom.bccs.common.tracing.MdcConstants;
import com.telecom.bccs.common.tracing.TraceContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Populates the MDC from the context headers injected by the gateway (X-Trace-Id, X-Client-Id,
 * X-User-Id) so every downstream log line is correlated. Generates a trace id if the request
 * somehow arrives without one (e.g. internal/manual call).
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceContextFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        try {
            String traceId = header(request, MdcConstants.HEADER_TRACE_ID, TraceContext.newTraceId());
            String spanId = header(request, MdcConstants.HEADER_SPAN_ID, TraceContext.newSpanId());

            MDC.put(MdcConstants.MDC_TRACE_ID, traceId);
            MDC.put(MdcConstants.MDC_SPAN_ID, spanId);
            MDC.put(MdcConstants.MDC_CLIENT_ID, header(request, MdcConstants.HEADER_CLIENT_ID, "unknown"));
            MDC.put(MdcConstants.MDC_USER_ID, header(request, MdcConstants.HEADER_USER_ID, "unknown"));
            MDC.put(MdcConstants.MDC_SERVICE_NAME, "product-catalog-service");

            response.setHeader(MdcConstants.HEADER_TRACE_ID, traceId);
            filterChain.doFilter(request, response);
        } finally {
            MDC.clear();
        }
    }

    private String header(HttpServletRequest request, String name, String fallback) {
        String value = request.getHeader(name);
        return (value == null || value.isBlank()) ? fallback : value;
    }
}
