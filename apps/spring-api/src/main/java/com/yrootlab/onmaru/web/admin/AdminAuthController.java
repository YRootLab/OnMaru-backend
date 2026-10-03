package com.yrootlab.onmaru.web.admin;

import com.yrootlab.onmaru.admin.auth.AdminAuthenticator;
import com.yrootlab.onmaru.admin.auth.AdminLoginResult;
import com.yrootlab.onmaru.admin.auth.AdminLoginService;
import com.yrootlab.onmaru.admin.auth.AdminPrincipal;
import com.yrootlab.onmaru.admin.auth.AdminSessionService;
import com.yrootlab.onmaru.web.common.error.ApiErrorResponse;
import com.yrootlab.onmaru.web.common.error.RequestIdFilter;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.ResponseCookie;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

@Tag(name = "AdminAuth", description = "관리자 access token 인증과 principal 조회")
@RestController
public final class AdminAuthController {

    private static final String REFRESH_COOKIE = "__Host-onmaru-admin-refresh";

    private final AdminAuthenticator authenticator;
    private final AdminLoginService loginService;
    private final AdminSessionService sessionService;

    public AdminAuthController(
            AdminAuthenticator authenticator,
            AdminLoginService loginService,
            AdminSessionService sessionService) {
        this.authenticator = authenticator;
        this.loginService = loginService;
        this.sessionService = sessionService;
    }

    @Operation(summary = "관리자 로그인")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "access token 발급"),
            @ApiResponse(responseCode = "400", description = "로그인 요청 형식 오류"),
            @ApiResponse(responseCode = "401", description = "관리자 인증 실패")
    })
    @PostMapping("/api/v1/auth/admin/login")
    public ResponseEntity<?> login(@RequestBody AdminLoginRequest body, HttpServletRequest request) {
        try {
            if (body == null) {
                throw new IllegalArgumentException("login body is required");
            }
            AdminLoginResult result = loginService.login(body.email(), body.password());
            return ResponseEntity.ok()
                    .cacheControl(CacheControl.noStore())
                    .header("Set-Cookie", refreshCookie(result.refreshToken()).toString())
                    .body(new AdminLoginResponse(
                            "1.0",
                            result.accessToken(),
                            result.expiresInSeconds(),
                            new AdminPrincipalResponse(
                                    result.principal().id(),
                                    result.principal().email(),
                                    result.principal().role().name())));
        } catch (IllegalArgumentException exception) {
            return ResponseEntity.badRequest()
                    .cacheControl(CacheControl.noStore())
                    .body(error("VALIDATION_ERROR", "Valid admin login credentials are required.", request));
        } catch (RuntimeException exception) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .cacheControl(CacheControl.noStore())
                    .body(error("AUTH_REQUIRED", "Admin authentication is required.", request));
        }
    }

    @Operation(summary = "관리자 access token 갱신")
    @PostMapping("/api/v1/auth/admin/refresh")
    public ResponseEntity<?> refresh(
            @org.springframework.web.bind.annotation.CookieValue(name = REFRESH_COOKIE, required = false) String refreshToken,
            HttpServletRequest request) {
        try {
            var result = sessionService.refresh(refreshToken);
            return ResponseEntity.ok()
                    .cacheControl(CacheControl.noStore())
                    .header("Set-Cookie", refreshCookie(result.refreshToken()).toString())
                    .body(new AdminLoginResponse(
                            "1.0", result.accessToken(), result.expiresInSeconds(),
                            new AdminPrincipalResponse(
                                    result.principal().id(), result.principal().email(), result.principal().role().name())));
        } catch (RuntimeException exception) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .cacheControl(CacheControl.noStore())
                    .body(error("AUTH_REQUIRED", "Admin authentication is required.", request));
        }
    }

    @Operation(summary = "관리자 로그아웃")
    @PostMapping("/api/v1/auth/admin/logout")
    public ResponseEntity<?> logout(
            @RequestHeader(name = "Authorization", required = false) String authorization,
            @org.springframework.web.bind.annotation.CookieValue(name = REFRESH_COOKIE, required = false) String refreshToken,
            HttpServletRequest request) {
        try {
            authenticator.authenticate(authorization);
            sessionService.revoke(refreshToken);
            return ResponseEntity.noContent()
                    .cacheControl(CacheControl.noStore())
                    .header("Set-Cookie", clearRefreshCookie().toString())
                    .build();
        } catch (RuntimeException exception) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .cacheControl(CacheControl.noStore())
                    .body(error("AUTH_REQUIRED", "Admin authentication is required.", request));
        }
    }

    @Operation(summary = "현재 관리자 조회")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "현재 관리자 principal"),
            @ApiResponse(responseCode = "401", description = "유효한 관리자 Bearer token 필요")
    })
    @GetMapping("/api/v1/auth/admin/me")
    public ResponseEntity<?> me(
            @RequestHeader(name = "Authorization", required = false) String authorization,
            HttpServletRequest request) {
        try {
            AdminPrincipal principal = authenticator.authenticate(authorization);
            return ResponseEntity.ok()
                    .cacheControl(CacheControl.noStore())
                    .body(new AdminPrincipalResponse(
                            principal.id(), principal.email(), principal.role().name()));
        } catch (RuntimeException exception) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .cacheControl(CacheControl.noStore())
                    .body(new ApiErrorResponse(
                            "1.2", "AUTH_REQUIRED", "Admin authentication is required.", requestId(request), Map.of()));
        }
    }

    private String requestId(HttpServletRequest request) {
        Object attribute = request.getAttribute(RequestIdFilter.ATTRIBUTE);
        if (attribute instanceof String requestId && !requestId.isBlank()) {
            return requestId;
        }
        String header = request.getHeader(RequestIdFilter.HEADER);
        return header == null || header.isBlank() ? UUID.randomUUID().toString() : header;
    }

    private ApiErrorResponse error(String code, String message, HttpServletRequest request) {
        return new ApiErrorResponse("1.2", code, message, requestId(request), Map.of());
    }

    private ResponseCookie refreshCookie(String value) {
        return ResponseCookie.from(REFRESH_COOKIE, value)
                .httpOnly(true)
                .secure(true)
                .sameSite("Strict")
                .path("/")
                .maxAge(java.time.Duration.ofDays(14))
                .build();
    }

    private ResponseCookie clearRefreshCookie() {
        return ResponseCookie.from(REFRESH_COOKIE, "")
                .httpOnly(true)
                .secure(true)
                .sameSite("Strict")
                .path("/")
                .maxAge(java.time.Duration.ZERO)
                .build();
    }

    private record AdminLoginRequest(String email, String password) {
    }

    private record AdminLoginResponse(
            String schemaVersion,
            String accessToken,
            long expiresIn,
            AdminPrincipalResponse admin) {
    }

    private record AdminPrincipalResponse(UUID id, String email, String role) {
    }
}
