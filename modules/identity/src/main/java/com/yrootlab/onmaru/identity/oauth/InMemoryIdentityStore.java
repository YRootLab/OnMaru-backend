package com.yrootlab.onmaru.identity.oauth;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public final class InMemoryIdentityStore implements IdentityStore {

    private final Map<String, OAuthStateRecord> states = new HashMap<>();
    private final Map<ExternalIdentity, UUID> externalAccounts = new HashMap<>();
    private final Map<UUID, Instant> members = new HashMap<>();
    private final Map<String, SessionRecord> sessions = new HashMap<>();

    @Override
    public synchronized void saveOAuthState(OAuthStateRecord state) {
        states.put(state.stateHash(), state);
    }

    @Override
    public synchronized Optional<OAuthStateRecord> consumeOAuthState(
            String stateHash,
            String provider,
            String browserNonceHash,
            String pkceVerifierHash,
            Instant now) {
        var state = states.get(stateHash);
        if (state == null
                || state.consumedAt() != null
                || !state.provider().equals(provider)
                || !state.browserNonceHash().equals(browserNonceHash)
                || !state.pkceVerifierHash().equals(pkceVerifierHash)
                || !state.expiresAt().isAfter(now)) {
            return Optional.empty();
        }
        var consumed = state.consume(now);
        states.put(stateHash, consumed);
        return Optional.of(consumed);
    }

    @Override
    public synchronized UUID linkExternalIdentity(ExternalIdentity identity, Instant now) {
        return externalAccounts.computeIfAbsent(identity, ignored -> {
            var memberId = UUID.randomUUID();
            members.put(memberId, now);
            return memberId;
        });
    }

    @Override
    public synchronized void saveSession(SessionRecord session) {
        sessions.put(session.tokenHash(), session);
    }

    int memberCount() {
        return members.size();
    }

    int externalAccountCount() {
        return externalAccounts.size();
    }

    int sessionCount() {
        return sessions.size();
    }
}
