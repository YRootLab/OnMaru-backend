package com.yrootlab.onmaru.admin.users;

import com.yrootlab.onmaru.admin.auth.AdminPrincipal;
import com.yrootlab.onmaru.identity.lifecycle.MemberLifecycleStore;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class AdminSanctionService {

    private final AdminSanctionStore store;
    private final Clock clock;
    private final MemberLifecycleStore memberSessions;

    public AdminSanctionService(AdminSanctionStore store, Clock clock) {
        this(store, clock, null);
    }

    public AdminSanctionService(AdminSanctionStore store, Clock clock, MemberLifecycleStore memberSessions) {
        this.store = store;
        this.clock = clock;
        this.memberSessions = memberSessions;
    }

    public List<AdminSanction> find(UUID memberId) {
        if (memberId == null) {
            throw new IllegalArgumentException("memberId is required");
        }
        store.expireDue(clock.instant());
        return store.findByMember(memberId);
    }

    public AdminSanction create(
            AdminPrincipal actor, UUID memberId, String reason, Instant startsAt, Instant endsAt) {
        requireAdmin(actor);
        if (memberId == null || reason == null || reason.isBlank()
                || reason.trim().length() > 300 || startsAt == null
                || (endsAt != null && !endsAt.isAfter(startsAt))) {
            throw new IllegalArgumentException("invalid sanction");
        }
        var sanction = store.create(memberId, actor.id(), reason.trim(), startsAt, endsAt);
        if (!startsAt.isAfter(clock.instant()) && memberSessions != null) {
            memberSessions.revokeAllSessions(memberId, clock.instant());
        }
        return sanction;
    }

    public boolean revoke(AdminPrincipal actor, UUID sanctionId) {
        requireAdmin(actor);
        if (sanctionId == null) {
            throw new IllegalArgumentException("sanctionId is required");
        }
        return store.revoke(sanctionId, actor.id(), clock.instant());
    }

    private void requireAdmin(AdminPrincipal actor) {
        if (actor == null || !actor.role().canManageUsers()) {
            throw new SecurityException("admin role required");
        }
    }
}
