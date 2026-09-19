package com.yrootlab.onmaru.security.oauth.kakao;

import com.yrootlab.onmaru.identity.oauth.CompleteOAuthLoginCommand;
import com.yrootlab.onmaru.identity.oauth.OAuthLoginService;
import com.yrootlab.onmaru.identity.oauth.OAuthProvider;
import com.yrootlab.onmaru.identity.oauth.StartOAuthLoginCommand;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Base64;
import java.util.UUID;

@Tag(name = "05. 개인화 & 타임라인 (Saved & Timeline)", description = "북마크/저장한 장소 및 오디오 도슨트, 내 활동 타임라인 및 회원 프로필 API")
@RestController
public final class KakaoOAuthController {

    private static final String NONCE_COOKIE = "__Host-onmaru-oauth-nonce";
    private static final String VERIFIER_COOKIE = "__Host-onmaru-oauth-verifier";
    private static final String SESSION_COOKIE = "__Host-onmaru-session";
    private static final OAuthProvider KAKAO = new OAuthProvider("KAKAO", "https://kauth.kakao.com");

    private final OAuthLoginService oauthLoginService;
    private final KakaoOAuthClient kakaoOAuthClient;
    private final KakaoOAuthProperties properties;
    private final OAuthNonceGenerator nonceGenerator;

    KakaoOAuthController(
            OAuthLoginService oauthLoginService,
            KakaoOAuthClient kakaoOAuthClient,
            KakaoOAuthProperties properties,
            OAuthNonceGenerator nonceGenerator) {
        this.oauthLoginService = oauthLoginService;
        this.kakaoOAuthClient = kakaoOAuthClient;
        this.properties = properties;
        this.nonceGenerator = nonceGenerator;
    }

    @Operation(
            summary = "카카오 소셜 로그인 시작 (OAuth2 인가 코드 요청)",
            description = "PKCE 보안 검증 및 Nonce 쿠키를 설정하고 카카오 로그인 인증 페이지로 302 리다이렉트합니다."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "302", description = "카카오 인증 서버로 리다이렉트")
    })
    @GetMapping("/auth/kakao/login")
    ResponseEntity<Void> startLogin(
            @Parameter(description = "로그인 완료 후 이동할 프론트엔드 경로", example = "/discover")
            @RequestParam(defaultValue = "/discover") String returnTo,
            @Parameter(description = "게스트 탐색 세션과 회원 연동을 위한 탐색 UUID", example = "a1b2c3d4-e5f6-7a8b-9c0d-1e2f3a4b5c6d")
            @RequestParam(required = false) UUID explorationId) {
        var nonce = nonceGenerator.generate();
        var verifier = nonceGenerator.generate();
        var started = oauthLoginService.startLogin(new StartOAuthLoginCommand(
                KAKAO,
                nonce,
                verifier,
                explorationId,
                returnTo));
        var location = UriComponentsBuilder.fromUriString(properties.getAuthorizeUri())
                .queryParam("response_type", "code")
                .queryParam("client_id", properties.getClientId())
                .queryParam("redirect_uri", properties.getRedirectUri())
                .queryParam("code_challenge", codeChallenge(verifier))
                .queryParam("code_challenge_method", "S256")
                .queryParam("state", started.state())
                .build()
                .toUri();
        return noStoreRedirect(302, location, nonceCookie(nonce), verifierCookie(verifier));
    }

    @Operation(
            summary = "카카오 소셜 로그인 콜백 (인가 코드 수신 및 세션 발급)",
            description = "카카오 인증 서버로부터 수신한 인가 코드를 검증하고 토큰을 교환하여 회원 세션 쿠키(__Host-onmaru-session)를 발급한 뒤 프론트엔드로 303 리다이렉트합니다."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "303", description = "로그인 성공 후 결과 페이지로 리다이렉트")
    })
    @GetMapping("/auth/kakao/callback")
    ResponseEntity<Void> completeLogin(
            @Parameter(description = "카카오 인가 코드")
            @RequestParam(required = false) String code,
            @Parameter(description = "OAuth 상태 토큰 (CSRF 방어용)")
            @RequestParam(required = false) String state,
            @Parameter(description = "오류 코드 (인증 실패/취소 시)")
            @RequestParam(required = false) String error,
            @Parameter(description = "브라우저 Nonce 쿠키", hidden = true)
            @org.springframework.web.bind.annotation.CookieValue(name = NONCE_COOKIE, required = false) String browserNonce,
            @Parameter(description = "PKCE 코드 검증자 쿠키", hidden = true)
            @org.springframework.web.bind.annotation.CookieValue(name = VERIFIER_COOKIE, required = false) String codeVerifier) {
        if (error != null
                || code == null
                || code.isBlank()
                || state == null
                || state.isBlank()
                || browserNonce == null
                || codeVerifier == null) {
            return noStoreRedirect(303, URI.create("/discover?auth=failed"), expireNonceCookie(), expireVerifierCookie());
        }
        try {
            var identity = kakaoOAuthClient.authenticate(code, codeVerifier);
            var result = oauthLoginService.completeLogin(new CompleteOAuthLoginCommand(
                    KAKAO,
                    state,
                    browserNonce,
                    codeVerifier,
                    identity));
            return noStoreRedirect(
                    303,
                    URI.create(withAuthStatus(result.returnPath(), "success")),
                    sessionCookie(result.sessionToken()),
                    expireNonceCookie(),
                    expireVerifierCookie());
        } catch (RuntimeException exception) {
            return noStoreRedirect(303, URI.create("/discover?auth=failed"), expireNonceCookie(), expireVerifierCookie());
        }
    }

    private ResponseEntity<Void> noStoreRedirect(int status, URI location, ResponseCookie... cookies) {
        var builder = ResponseEntity.status(status)
                .cacheControl(CacheControl.noStore())
                .header(HttpHeaders.LOCATION, location.toString());
        for (var cookie : cookies) {
            builder.header(HttpHeaders.SET_COOKIE, cookie.toString());
        }
        return builder.build();
    }

    private ResponseCookie nonceCookie(String nonce) {
        return ResponseCookie.from(NONCE_COOKIE, nonce)
                .path("/")
                .secure(true)
                .httpOnly(true)
                .sameSite("Lax")
                .maxAge(Duration.ofMinutes(10))
                .build();
    }

    private ResponseCookie verifierCookie(String verifier) {
        return ResponseCookie.from(VERIFIER_COOKIE, verifier)
                .path("/")
                .secure(true)
                .httpOnly(true)
                .sameSite("Lax")
                .maxAge(Duration.ofMinutes(10))
                .build();
    }

    private ResponseCookie sessionCookie(String sessionToken) {
        return ResponseCookie.from(SESSION_COOKIE, sessionToken)
                .path("/")
                .secure(true)
                .httpOnly(true)
                .sameSite("Lax")
                .maxAge(Duration.ofDays(7))
                .build();
    }

    private ResponseCookie expireNonceCookie() {
        return ResponseCookie.from(NONCE_COOKIE, "")
                .path("/")
                .secure(true)
                .httpOnly(true)
                .sameSite("Lax")
                .maxAge(Duration.ZERO)
                .build();
    }

    private ResponseCookie expireVerifierCookie() {
        return ResponseCookie.from(VERIFIER_COOKIE, "")
                .path("/")
                .secure(true)
                .httpOnly(true)
                .sameSite("Lax")
                .maxAge(Duration.ZERO)
                .build();
    }

    private String codeChallenge(String verifier) {
        try {
            var digest = MessageDigest.getInstance("SHA-256")
                    .digest(verifier.getBytes(StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }

    private String withAuthStatus(String returnPath, String status) {
        var separator = returnPath.contains("?") ? "&" : "?";
        return returnPath + separator + "auth=" + status;
    }
}
