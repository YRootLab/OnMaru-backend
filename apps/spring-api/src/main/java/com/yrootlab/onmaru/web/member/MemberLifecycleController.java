package com.yrootlab.onmaru.web.member;

import com.yrootlab.onmaru.identity.lifecycle.MemberLifecycleService;
import com.yrootlab.onmaru.identity.lifecycle.MemberSessionRequiredException;
import com.yrootlab.onmaru.identity.lifecycle.MemberSummary;
import com.yrootlab.onmaru.web.common.error.RequestIdFilter;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
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

@Tag(name = "05. 개인화 & 타임라인 (Saved & Timeline)", description = "북마크/저장한 장소 및 오디오 도슨트, 내 활동 타임라인 및 회원 프로필 API")
@RestController
public final class MemberLifecycleController {

    private static final String SESSION_COOKIE = "__Host-onmaru-session";

    private final MemberLifecycleService lifecycleService;

    MemberLifecycleController(MemberLifecycleService lifecycleService) {
        this.lifecycleService = lifecycleService;
    }

    @Operation(
            summary = "현재 로그인한 내 프로필 조회",
            description = "세션 쿠키 기반으로 현재 인증된 회원의 ID 및 닉네임 정보를 조회합니다."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "회원 정보 조회 성공"),
            @ApiResponse(responseCode = "401", description = "로그인 세션 필요")
    })
    @GetMapping("/api/v1/members/me")
    ResponseEntity<?> currentMember(
            @Parameter(description = "회원 세션 쿠키", hidden = true)
            @CookieValue(name = SESSION_COOKIE, required = false) String sessionToken,
            HttpServletRequest request) {
        return lifecycleService.currentMember(sessionToken)
                .<ResponseEntity<?>>map(member -> ResponseEntity.ok()
                        .cacheControl(CacheControl.noStore())
                        .body(MemberMeResponse.from(member)))
                .orElseGet(() -> authRequired(request));
    }

    @Operation(
            summary = "로그아웃",
            description = "현재 활성화된 회원 세션을 만료시키고 세션 쿠키를 삭제합니다."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "로그아웃 완료 및 쿠키 파기")
    })
    @PostMapping("/api/v1/auth/logout")
    ResponseEntity<Void> logout(
            @Parameter(description = "회원 세션 쿠키", hidden = true)
            @CookieValue(name = SESSION_COOKIE, required = false) String sessionToken,
            @RequestBody(required = false) Map<String, Object> ignored) {
        lifecycleService.logout(sessionToken);
        return ResponseEntity.noContent()
                .cacheControl(CacheControl.noStore())
                .header(HttpHeaders.SET_COOKIE, expireSessionCookie().toString())
                .build();
    }

    @Operation(
            summary = "회원 탈퇴 및 계정 삭제 요청",
            description = "회원 탈퇴를 접수하고 세션을 즉시 만료시키며 비식별화/삭제 프로세스를 시작합니다."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "202", description = "회원 탈퇴 요청 접수 완료"),
            @ApiResponse(responseCode = "401", description = "로그인 세션 필요")
    })
    @DeleteMapping("/api/v1/members/me")
    ResponseEntity<?> deleteCurrentMember(
            @Parameter(description = "회원 세션 쿠키", hidden = true)
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
