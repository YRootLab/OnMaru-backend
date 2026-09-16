package com.yrootlab.onmaru.security.web;

import com.yrootlab.onmaru.web.common.error.ApiErrorCode;
import com.yrootlab.onmaru.web.common.error.RequestIdFilter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.CacheControl;
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

    CsrfProtectionFilter(CsrfTokenService tokenService) {
        this.tokenService = tokenService;
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
        return isSameOrigin(request)
                && tokenService.matches(cookieToken(request), request.getHeader(CsrfTokenService.HEADER_NAME));
    }

    private boolean isSameOrigin(HttpServletRequest request) {
        var origin = request.getHeader("Origin");
        if (origin == null || origin.isBlank()) {
            return true;
        }
        return origin.equals(requestOrigin(request));
    }

    private String requestOrigin(HttpServletRequest request) {
        var scheme = request.getScheme();
        var host = request.getServerName();
        var port = request.getServerPort();
        var defaultPort = ("https".equals(scheme) && port == 443) || ("http".equals(scheme) && port == 80);
        return defaultPort ? "%s://%s".formatted(scheme, host) : "%s://%s:%d".formatted(scheme, host, port);
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
        response.setHeader("Cache-Control", CacheControl.noStore().getHeaderValue());
        response.getWriter().write("""
                {"schemaVersion":"1.2","code":"CSRF_INVALID","message":"%s","requestId":"%s","details":{}}
                """.formatted(ApiErrorCode.CSRF_INVALID.message(), escapeJson(requestId(request))).trim());
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
