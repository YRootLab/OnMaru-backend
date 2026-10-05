package com.yrootlab.onmaru.identity.oauth;

import com.yrootlab.onmaru.identity.profile.NewMemberProfile;

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

    Optional<UUID> findLinkedMemberId(ExternalIdentity identity);

    UUID linkExternalIdentity(ExternalIdentity identity, NewMemberProfile profile, Instant now);

    void saveSession(SessionRecord session);
}
