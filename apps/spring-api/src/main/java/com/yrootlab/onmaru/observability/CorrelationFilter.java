package com.yrootlab.onmaru.observability;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerMapping;

@Component
class CorrelationFilter extends OncePerRequestFilter {

    private final TelemetrySink telemetrySink;

    CorrelationFilter(TelemetrySink telemetrySink) {
        this.telemetrySink = telemetrySink;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        CorrelationContext context = CorrelationContext.from(request);
        putMdc(context);
        response.setHeader("X-Request-Id", context.requestId());
        response.setHeader("X-Run-Id", context.runId());
        response.setHeader("X-Revision", context.revision());

        try {
            filterChain.doFilter(request, response);
        } finally {
            telemetrySink.record(new TelemetryEvent("http.server.request", attributes(request, response, context)));
            MDC.clear();
        }
    }

    private static void putMdc(CorrelationContext context) {
        MDC.put("requestId", context.requestId());
        MDC.put("traceId", context.traceId());
        MDC.put("runId", context.runId());
        MDC.put("revision", context.revision());
    }

    private static Map<String, String> attributes(
            HttpServletRequest request,
            HttpServletResponse response,
            CorrelationContext context) {
        Map<String, String> attributes = new LinkedHashMap<>();
        attributes.put("request.id", context.requestId());
        attributes.put("trace.id", context.traceId());
        attributes.put("run.id", context.runId());
        attributes.put("revision", context.revision());
        attributes.put("http.request.method", request.getMethod());
        attributes.put("http.route", route(request));
        attributes.put("http.response.status_code", Integer.toString(response.getStatus()));
        return attributes;
    }

    private static String route(HttpServletRequest request) {
        Object pattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        if (pattern instanceof String routePattern && !routePattern.isBlank()) {
            return routePattern;
        }
        return "unmatched";
    }
}
