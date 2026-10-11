package com.yrootlab.onmaru.web.admission;

import com.yrootlab.onmaru.operations.admission.AdmissionPolicy;
import com.yrootlab.onmaru.operations.admission.AdmissionRequest;
import com.yrootlab.onmaru.operations.admission.AdmissionService;
import com.yrootlab.onmaru.operations.admission.AdmissionSubject;
import com.yrootlab.onmaru.operations.admission.SubjectType;
import com.yrootlab.onmaru.web.common.error.ApiErrorCode;
import com.yrootlab.onmaru.web.common.error.ApiErrorResponse;
import com.yrootlab.onmaru.web.common.error.RequestIdFilter;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public final class AdmissionFilter extends OncePerRequestFilter {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final Pattern DISCOVERY_READ = Pattern.compile(
            "^/api/v1/(?:discovery/(?:topics|places(?:/[^/]+)?)|k-contents/works(?:/[^/]+)?|places/[^/]+/k-contents)$");

    private final AdmissionService admissionService;
    private final AdmissionPolicy admissionPolicy;
    private final ClientIdentityResolver identityResolver;
    private final boolean discoveryEnabled;

    public AdmissionFilter(
            AdmissionService admissionService,
            AdmissionPolicy admissionPolicy,
            ClientIdentityResolver identityResolver,
            boolean discoveryEnabled
    ) {
        this.admissionService = admissionService;
        this.admissionPolicy = admissionPolicy;
        this.identityResolver = identityResolver;
        this.discoveryEnabled = discoveryEnabled;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        var operation = operationFor(request);
        if (operation == null) {
            filterChain.doFilter(request, response);
            return;
        }

        var decision = admissionService.admit(new AdmissionRequest(
                operation,
                new AdmissionSubject(SubjectType.IP, identityResolver.clientIp(request))
        ), admissionPolicy);
        if (decision.allowed()) {
            filterChain.doFilter(request, response);
            return;
        }

        writeRateLimited(request, response, decision.retryAfter());
    }

    private String operationFor(HttpServletRequest request) {
        if (discoveryEnabled && "GET".equals(request.getMethod())
                && DISCOVERY_READ.matcher(request.getRequestURI()).matches()) {
            return "discovery.read";
        }
        if ("POST".equals(request.getMethod()) && "/api/admission/login".equals(request.getRequestURI())) {
            return "login.start";
        }
        if ("POST".equals(request.getMethod()) && "/api/v1/visit-reviews".equals(request.getRequestURI())) {
            return "review.create.external";
        }
        return null;
    }

    private void writeRateLimited(
            HttpServletRequest request,
            HttpServletResponse response,
            Duration retryAfter
    ) throws IOException {
        long retryAfterSeconds = Math.max(1, retryAfter.toSeconds());
        response.setStatus(ApiErrorCode.RATE_LIMITED.status().value());
        response.setHeader("Retry-After", Long.toString(retryAfterSeconds));
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        OBJECT_MAPPER.writeValue(response.getWriter(), ApiErrorResponse.of(
                ApiErrorCode.RATE_LIMITED,
                requestId(request),
                Map.of("retryAfterMs", retryAfter.toMillis())
        ));
    }

    private String requestId(HttpServletRequest request) {
        var fromAttribute = request.getAttribute(RequestIdFilter.ATTRIBUTE);
        if (fromAttribute instanceof String requestId && !requestId.isBlank()) {
            return requestId;
        }
        var fromHeader = request.getHeader(RequestIdFilter.HEADER);
        return fromHeader == null || fromHeader.isBlank() ? UUID.randomUUID().toString() : fromHeader;
    }
}
