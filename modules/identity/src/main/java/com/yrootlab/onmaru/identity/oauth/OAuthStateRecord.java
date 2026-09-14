package com.yrootlab.onmaru.identity.oauth;

import java.time.Instant;
import java.util.UUID;

public record OAuthStateRecord(
        String stateHash,
        UUID guestId,
        String browserNonceHash,
        String pkceVerifierHash,
        UUID explorationId,
        String provider,
        String returnPath,
        Instant expiresAt,
        Instant consumedAt) {

    OAuthStateRecord consume(Instant now) {
        return new OAuthStateRecord(
                stateHash,
                guestId,
                browserNonceHash,
                pkceVerifierHash,
                explorationId,
                provider,
                returnPath,
                expiresAt,
                now);
    }
}
