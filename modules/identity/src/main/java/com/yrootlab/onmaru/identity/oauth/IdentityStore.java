package com.yrootlab.onmaru.identity.oauth;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface IdentityStore {

    void saveOAuthState(OAuthStateRecord state);

    Optional<OAuthStateRecord> consumeOAuthState(
            String stateHash,
            String provider,
            String browserNonceHash,
            String pkceVerifierHash,
            Instant now);

    UUID linkExternalIdentity(ExternalIdentity identity, Instant now);

    void saveSession(SessionRecord session);
}
