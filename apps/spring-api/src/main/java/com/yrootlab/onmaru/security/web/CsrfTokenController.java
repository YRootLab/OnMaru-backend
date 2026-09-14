package com.yrootlab.onmaru.security.web;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;

@RestController
public final class CsrfTokenController {

    private final CsrfTokenService tokenService;

    CsrfTokenController(CsrfTokenService tokenService) {
        this.tokenService = tokenService;
    }

    @GetMapping("/auth/csrf")
    ResponseEntity<CsrfTokenResponse> csrfToken() {
        var token = tokenService.issueToken();
        var cookie = ResponseCookie.from(CsrfTokenService.COOKIE_NAME, token)
                .path("/")
                .secure(true)
                .httpOnly(true)
                .sameSite("Lax")
                .maxAge(Duration.ofMinutes(30))
                .build();
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .header(HttpHeaders.SET_COOKIE, cookie.toString())
                .body(new CsrfTokenResponse(token, CsrfTokenService.HEADER_NAME));
    }

    record CsrfTokenResponse(String token, String headerName) {
    }
}
