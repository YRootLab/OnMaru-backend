package com.yrootlab.onmaru.web.member;

import com.yrootlab.onmaru.identity.lifecycle.MemberLifecycleService;
import com.yrootlab.onmaru.identity.lifecycle.MemberSessionRequiredException;
import com.yrootlab.onmaru.identity.lifecycle.MemberSummary;
import com.yrootlab.onmaru.web.common.error.RequestIdFilter;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;
import java.time.Duration;
import java.util.Map;

@RestController
public final class MemberLifecycleController {

    private static final String SESSION_COOKIE = "__Host-onmaru-session";

    private final MemberLifecycleService lifecycleService;

    MemberLifecycleController(MemberLifecycleService lifecycleService) {
        this.lifecycleService = lifecycleService;
    }

    @GetMapping("/api/v1/members/me")
    ResponseEntity<?> currentMember(
            @CookieValue(name = SESSION_COOKIE, required = false) String sessionToken,
            HttpServletRequest request) {
        return lifecycleService.currentMember(sessionToken)
                .<ResponseEntity<?>>map(member -> ResponseEntity.ok()
                        .cacheControl(CacheControl.noStore())
                        .body(MemberMeResponse.from(member)))
                .orElseGet(() -> authRequired(request));
    }

    @PostMapping("/api/v1/auth/logout")
    ResponseEntity<Void> logout(
            @CookieValue(name = SESSION_COOKIE, required = false) String sessionToken,
            @RequestBody(required = false) Map<String, Object> ignored) {
        lifecycleService.logout(sessionToken);
        return ResponseEntity.noContent()
                .cacheControl(CacheControl.noStore())
                .header(HttpHeaders.SET_COOKIE, expireSessionCookie().toString())
                .build();
    }

    @DeleteMapping("/api/v1/members/me")
    ResponseEntity<?> deleteCurrentMember(
            @CookieValue(name = SESSION_COOKIE, required = false) String sessionToken,
            HttpServletRequest request) {
        try {
            var result = lifecycleService.requestDeletion(sessionToken);
            return ResponseEntity.accepted()
                    .cacheControl(CacheControl.noStore())
                    .header(HttpHeaders.SET_COOKIE, expireSessionCookie().toString())
                    .body(new MemberDeletingStatusResponse("1.2", result.status().name()));
        } catch (MemberSessionRequiredException exception) {
            return authRequired(request);
        }
    }

    private ResponseEntity<Map<String, Object>> authRequired(HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .cacheControl(CacheControl.noStore())
                .body(Map.of(
                        "schemaVersion", "1.2",
                        "code", "AUTH_REQUIRED",
                        "message", "Authentication is required.",
                        "requestId", requestId(request),
                        "details", Map.of()));
    }

    private String requestId(HttpServletRequest request) {
        var attribute = request.getAttribute(RequestIdFilter.ATTRIBUTE);
        if (attribute instanceof String requestId && !requestId.isBlank()) {
            return requestId;
        }
        var header = request.getHeader(RequestIdFilter.HEADER);
        return header == null || header.isBlank() ? "unknown" : header;
    }

    private ResponseCookie expireSessionCookie() {
        return ResponseCookie.from(SESSION_COOKIE, "")
                .path("/")
                .secure(true)
                .httpOnly(true)
                .sameSite("Lax")
                .maxAge(Duration.ZERO)
                .build();
    }

    record MemberMeResponse(String schemaVersion, String id, String displayName) {

        static MemberMeResponse from(MemberSummary member) {
            return new MemberMeResponse("1.2", member.id().toString(), member.displayName());
        }
    }

    record MemberDeletingStatusResponse(String schemaVersion, String status) {
    }
}
