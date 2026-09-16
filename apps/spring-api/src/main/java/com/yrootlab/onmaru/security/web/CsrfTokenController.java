package com.yrootlab.onmaru.security.web;

import com.yrootlab.onmaru.identity.guest.GuestCredentialService;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;

@RestController
public final class CsrfTokenController {

    private final CsrfTokenService tokenService;
    private final GuestCredentialService guestCredentialService;

    CsrfTokenController(CsrfTokenService tokenService, GuestCredentialService guestCredentialService) {
        this.tokenService = tokenService;
        this.guestCredentialService = guestCredentialService;
    }

    @GetMapping("/auth/csrf")
    ResponseEntity<CsrfTokenResponse> csrfToken(
            @CookieValue(name = "__Host-onmaru-guest", required = false) String guestToken) {
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

    record CsrfTokenResponse(String token, String headerName) {
    }
}
