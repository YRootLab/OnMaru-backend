package com.yrootlab.onmaru.identity.lifecycle;

import com.yrootlab.onmaru.identity.oauth.TokenHasher;

import java.time.Clock;
import java.util.Optional;
import java.util.UUID;

public final class MemberLifecycleService {

    private final MemberLifecycleStore store;
    private final TokenHasher tokenHasher;
    private final Clock clock;
    private final MemberAccessPolicy accessPolicy;

    public MemberLifecycleService(MemberLifecycleStore store, TokenHasher tokenHasher, Clock clock) {
        this(store, tokenHasher, clock, MemberAccessPolicy.allowAll());
    }

    public MemberLifecycleService(
            MemberLifecycleStore store, TokenHasher tokenHasher, Clock clock, MemberAccessPolicy accessPolicy) {
        this.store = store;
        this.tokenHasher = tokenHasher;
        this.clock = clock;
        this.accessPolicy = accessPolicy;
    }

    public Optional<MemberSummary> currentMember(String sessionToken) {
        if (sessionToken == null || sessionToken.isBlank()) {
            return Optional.empty();
        }
        var now = clock.instant();
        return store.findActiveMemberBySessionHash(tokenHasher.hash(sessionToken), now)
                .filter(member -> accessPolicy.allows(member.id(), now));
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

    public int revokeAllSessions(UUID memberId) {
        return store.revokeAllSessions(memberId, clock.instant());
    }
}
