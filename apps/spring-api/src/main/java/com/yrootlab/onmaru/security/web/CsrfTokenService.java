package com.yrootlab.onmaru.security.web;

import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.util.Base64;

@Component
public final class CsrfTokenService {

    public static final String COOKIE_NAME = "__Host-onmaru-csrf";
    public static final String HEADER_NAME = "X-CSRF-TOKEN";

    private final SecureRandom secureRandom = new SecureRandom();

    public String issueToken() {
        var bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public boolean matches(String cookieToken, String headerToken) {
        if (cookieToken == null || headerToken == null || cookieToken.isBlank() || headerToken.isBlank()) {
            return false;
        }
        return constantTimeEquals(cookieToken, headerToken);
    }

    private boolean constantTimeEquals(String left, String right) {
        var leftBytes = left.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        var rightBytes = right.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        var diff = leftBytes.length ^ rightBytes.length;
        for (var index = 0; index < Math.min(leftBytes.length, rightBytes.length); index++) {
            diff |= leftBytes[index] ^ rightBytes[index];
        }
        return diff == 0;
    }
}
