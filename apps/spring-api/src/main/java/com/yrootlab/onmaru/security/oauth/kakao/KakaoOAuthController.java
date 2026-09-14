package com.yrootlab.onmaru.security.oauth.kakao;

import com.yrootlab.onmaru.identity.oauth.CompleteOAuthLoginCommand;
import com.yrootlab.onmaru.identity.oauth.OAuthLoginService;
import com.yrootlab.onmaru.identity.oauth.OAuthProvider;
import com.yrootlab.onmaru.identity.oauth.StartOAuthLoginCommand;
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

    @GetMapping("/auth/kakao/login")
    ResponseEntity<Void> startLogin(
            @RequestParam(defaultValue = "/discover") String returnTo,
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

    @GetMapping("/auth/kakao/callback")
    ResponseEntity<Void> completeLogin(
            @RequestParam(required = false) String code,
            @RequestParam(required = false) String state,
            @RequestParam(required = false) String error,
            @org.springframework.web.bind.annotation.CookieValue(name = NONCE_COOKIE, required = false) String browserNonce,
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
