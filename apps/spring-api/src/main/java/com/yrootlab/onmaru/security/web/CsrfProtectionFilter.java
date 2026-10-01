package com.yrootlab.onmaru.security.web;

import com.yrootlab.onmaru.config.CorsOriginPolicy;
import com.yrootlab.onmaru.web.common.error.ApiErrorCode;
import com.yrootlab.onmaru.web.common.error.RequestIdFilter;
import com.yrootlab.onmaru.web.stamp.StampApiContract;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Set;
import java.util.UUID;

@Component
public final class CsrfProtectionFilter extends OncePerRequestFilter {

    private static final Set<String> UNSAFE_METHODS = Set.of("POST", "PUT", "PATCH", "DELETE");

    private final CsrfTokenService tokenService;
    private final CorsOriginPolicy corsOriginPolicy;

    CsrfProtectionFilter(CsrfTokenService tokenService, CorsOriginPolicy corsOriginPolicy) {
        this.tokenService = tokenService;
        this.corsOriginPolicy = corsOriginPolicy;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        if (requiresCsrf(request) && !isValid(request)) {
            reject(request, response);
            return;
        }
        filterChain.doFilter(request, response);
    }

    private boolean requiresCsrf(HttpServletRequest request) {
        return request.getRequestURI().startsWith("/api/")
                && UNSAFE_METHODS.contains(request.getMethod());
    }

    private boolean isValid(HttpServletRequest request) {
        return isAllowedOrigin(request)
                && tokenService.matches(cookieToken(request), request.getHeader(CsrfTokenService.HEADER_NAME));
    }

    private boolean isAllowedOrigin(HttpServletRequest request) {
        var origin = request.getHeader("Origin");
        if (origin == null || origin.isBlank()) {
            return true;
        }
        return corsOriginPolicy.allows(origin);
    }

    private String cookieToken(HttpServletRequest request) {
        var cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        for (var cookie : cookies) {
            if (CsrfTokenService.COOKIE_NAME.equals(cookie.getName())) {
                return cookie.getValue();
            }
        }
        return null;
    }

    private void reject(HttpServletRequest request, HttpServletResponse response) throws IOException {
        response.setStatus(ApiErrorCode.CSRF_INVALID.status().value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setHeader(HttpHeaders.CACHE_CONTROL, CacheControl.noStore().getHeaderValue());
        response.getWriter().write("""
                {"schemaVersion":"%s","code":"CSRF_INVALID","message":"%s","requestId":"%s","details":{}}
                """.formatted(schemaVersion(request), ApiErrorCode.CSRF_INVALID.message(),
                        escapeJson(requestId(request))).trim());
    }

    private String schemaVersion(HttpServletRequest request) {
        var path = request.getRequestURI();
        return path.equals("/api/v1/me/stamp-ranking")
                || path.matches("/api/v1/places/[^/]+/check-ins")
                ? StampApiContract.SCHEMA_VERSION : "1.2";
    }

    private String requestId(HttpServletRequest request) {
        var fromAttribute = request.getAttribute(RequestIdFilter.ATTRIBUTE);
        if (fromAttribute instanceof String requestId && !requestId.isBlank()) {
            return requestId;
        }
        var fromHeader = request.getHeader(RequestIdFilter.HEADER);
        return fromHeader == null || fromHeader.isBlank() ? UUID.randomUUID().toString() : fromHeader;
    }

    private String escapeJson(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
