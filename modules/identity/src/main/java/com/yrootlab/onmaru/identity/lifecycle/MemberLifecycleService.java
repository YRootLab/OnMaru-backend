package com.yrootlab.onmaru.identity.lifecycle;

import com.yrootlab.onmaru.identity.oauth.TokenHasher;

import java.time.Clock;
import java.util.Optional;
import java.util.UUID;

public final class MemberLifecycleService {

    private final MemberLifecycleStore store;
    private final TokenHasher tokenHasher;
    private final Clock clock;

    public MemberLifecycleService(MemberLifecycleStore store, TokenHasher tokenHasher, Clock clock) {
        this.store = store;
        this.tokenHasher = tokenHasher;
        this.clock = clock;
    }

    public Optional<MemberSummary> currentMember(String sessionToken) {
        if (sessionToken == null || sessionToken.isBlank()) {
            return Optional.empty();
        }
        return store.findActiveMemberBySessionHash(tokenHasher.hash(sessionToken), clock.instant());
    }

    public void logout(String sessionToken) {
        if (sessionToken != null && !sessionToken.isBlank()) {
            store.revokeSession(tokenHasher.hash(sessionToken), clock.instant());
        }
    }

    public MemberDeletionResult requestDeletion(String sessionToken) {
        var status = store.requestDeletion(tokenHasher.hash(sessionToken), clock.instant())
                .orElseThrow(MemberSessionRequiredException::new);
        return new MemberDeletionResult(status);
    }

    public boolean allowsLateWrite(UUID memberId) {
        return store.allowsLateWrite(memberId);
    }
}
