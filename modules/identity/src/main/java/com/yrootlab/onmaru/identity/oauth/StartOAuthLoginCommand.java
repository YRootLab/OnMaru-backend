package com.yrootlab.onmaru.identity.oauth;

import java.util.UUID;

public record StartOAuthLoginCommand(
        OAuthProvider provider,
        String browserNonce,
        String pkceVerifier,
        UUID explorationId,
        String returnPath) {

    public StartOAuthLoginCommand {
        if (provider == null) {
            throw new IllegalArgumentException("provider must not be null");
        }
        if (browserNonce == null || browserNonce.isBlank()) {
            throw new IllegalArgumentException("browser nonce must not be blank");
        }
        if (pkceVerifier == null || pkceVerifier.isBlank()) {
            throw new IllegalArgumentException("PKCE verifier must not be blank");
        }
    }
}
