package com.chronoscore.coordinator.api.filter;

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
import java.util.UUID;

/**
 * Filter propagating Request and Correlation Identifiers into MDC structured logging
 * and HTTP response headers across all inbound REST traffic.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceAndCorrelationFilter extends OncePerRequestFilter {

    public static final String HEADER_REQUEST_ID = "X-Request-Id";
    public static final String HEADER_CORRELATION_ID = "X-Correlation-Id";
    public static final String HEADER_TRACE_ID = "X-Trace-Id";

    public static final String ATTR_REQUEST_ID = "chronos.requestId";
    public static final String ATTR_TRACE_ID = "chronos.traceId";

    public static final String MDC_REQUEST_ID = "requestId";
    public static final String MDC_TRACE_ID = "traceId";

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {

        String incomingRequestId = request.getHeader(HEADER_REQUEST_ID);
        String incomingCorrelationId = request.getHeader(HEADER_CORRELATION_ID);

        String requestId = (incomingRequestId != null && !incomingRequestId.isBlank())
                ? incomingRequestId.trim()
                : (incomingCorrelationId != null && !incomingCorrelationId.isBlank()
                    ? incomingCorrelationId.trim()
                    : "req-" + UUID.randomUUID().toString().substring(0, 8));

        String traceId = request.getHeader(HEADER_TRACE_ID);
        if (traceId == null || traceId.isBlank()) {
            traceId = UUID.randomUUID().toString().replace("-", "");
        }

        request.setAttribute(ATTR_REQUEST_ID, requestId);
        request.setAttribute(ATTR_TRACE_ID, traceId);

        MDC.put(MDC_REQUEST_ID, requestId);
        MDC.put(MDC_TRACE_ID, traceId);

        response.setHeader(HEADER_REQUEST_ID, requestId);
        response.setHeader(HEADER_CORRELATION_ID, requestId);
        response.setHeader(HEADER_TRACE_ID, traceId);

        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_REQUEST_ID);
            MDC.remove(MDC_TRACE_ID);
        }
    }
}
