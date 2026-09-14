package com.yrootlab.onmaru.security.oauth.kakao;

import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.util.Base64;

@Component
public final class OAuthNonceGenerator {

    private final SecureRandom secureRandom = new SecureRandom();

    String generate() {
        var bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
