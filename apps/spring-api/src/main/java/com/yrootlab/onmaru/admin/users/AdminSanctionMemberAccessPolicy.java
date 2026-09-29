package com.yrootlab.onmaru.admin.users;

import com.yrootlab.onmaru.identity.lifecycle.MemberAccessPolicy;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

/** Bridges administrator sanctions into the shared member access boundary. */
public final class AdminSanctionMemberAccessPolicy implements MemberAccessPolicy {

    private final AdminSanctionStore store;
    private final Clock clock;

    public AdminSanctionMemberAccessPolicy(AdminSanctionStore store, Clock clock) {
        this.store = store;
        this.clock = clock;
    }

    @Override
    public boolean allows(UUID memberId, Instant now) {
        if (memberId == null) {
            return false;
        }
        Instant evaluatedAt = now == null ? clock.instant() : now;
        return store.findByMember(memberId).stream()
                .noneMatch(sanction -> "ACTIVE".equals(sanction.status())
                        && !sanction.startsAt().isAfter(evaluatedAt)
                        && (sanction.endsAt() == null || sanction.endsAt().isAfter(evaluatedAt)));
    }
}
