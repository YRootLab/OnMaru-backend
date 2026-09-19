package com.yrootlab.onmaru.security.web;

import com.yrootlab.onmaru.identity.guest.GuestCredentialService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;

@Tag(name = "05. 개인화 & 인증 (Auth & Saved)", description = "인증, CSRF 토큰 및 게스트 세션 관리 API")
@RestController
public final class CsrfTokenController {

    private final CsrfTokenService tokenService;
    private final GuestCredentialService guestCredentialService;

    CsrfTokenController(CsrfTokenService tokenService, GuestCredentialService guestCredentialService) {
        this.tokenService = tokenService;
        this.guestCredentialService = guestCredentialService;
    }

    @Operation(
            summary = "CSRF 토큰 및 게스트 세션 발급",
            description = "상태 변경(POST/PUT/PATCH/DELETE) API 호출 시 CSRF 방어를 위한 헤더 토큰 및 쿠키를 발급하며, 비회원인 경우 게스트 식별용 쿠키를 함께 설정합니다."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "CSRF 토큰 및 쿠키 발급 성공", content = @Content(schema = @Schema(implementation = CsrfTokenResponse.class)))
    })
    @GetMapping("/auth/csrf")
    ResponseEntity<CsrfTokenResponse> csrfToken(
            @Parameter(hidden = true) @CookieValue(name = "__Host-onmaru-guest", required = false) String guestToken) {
        var token = tokenService.issueToken();
        var cookie = ResponseCookie.from(CsrfTokenService.COOKIE_NAME, token)
                .path("/")
                .secure(true)
                .httpOnly(true)
                .sameSite("Lax")
                .maxAge(Duration.ofMinutes(30))
                .build();
        var response = ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .header(HttpHeaders.SET_COOKIE, cookie.toString());
        if (guestCredentialService.resolve(guestToken).isEmpty()) {
            var issued = guestCredentialService.issue();
            var guestCookie = ResponseCookie.from("__Host-onmaru-guest", issued.rawToken())
                    .path("/")
                    .secure(true)
                    .httpOnly(true)
                    .sameSite("Lax")
                    .maxAge(Duration.ofHours(24))
                    .build();
            response.header(HttpHeaders.SET_COOKIE, guestCookie.toString());
        }
        return response.body(new CsrfTokenResponse(token, CsrfTokenService.HEADER_NAME));
    }

    @Schema(description = "CSRF 토큰 발급 응답")
    record CsrfTokenResponse(
            @Schema(description = "CSRF 검증용 마스킹 토큰", example = "d94b15c7-8c35-430b-9304-4b53efba8a19")
            String token,
            @Schema(description = "클라이언트가 전송할 요청 헤더 키", example = "X-CSRF-TOKEN")
            String headerName
    ) {
    }
}
