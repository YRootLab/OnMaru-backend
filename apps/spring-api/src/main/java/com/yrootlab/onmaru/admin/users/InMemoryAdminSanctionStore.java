package com.yrootlab.onmaru.admin.users;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class InMemoryAdminSanctionStore implements AdminSanctionStore {

    private final List<AdminSanction> sanctions = new ArrayList<>();

    @Override
    public synchronized List<AdminSanction> findByMember(UUID memberId) {
        return sanctions.stream().filter(item -> item.memberId().equals(memberId)).toList();
    }

    @Override
    public synchronized AdminSanction create(
            UUID memberId, UUID adminId, String reason, Instant startsAt, Instant endsAt) {
        if (sanctions.stream().anyMatch(item -> item.memberId().equals(memberId)
                && "ACTIVE".equals(item.status()) && item.revokedAt() == null)) {
            throw new IllegalStateException("member already has an active sanction");
        }
        var sanction = new AdminSanction(
                UUID.randomUUID(), memberId, "ACTIVE", reason, startsAt, endsAt,
                adminId, null, null, startsAt);
        sanctions.add(sanction);
        return sanction;
    }

    @Override
    public synchronized boolean revoke(UUID sanctionId, UUID adminId, Instant revokedAt) {
        for (int index = 0; index < sanctions.size(); index++) {
            var current = sanctions.get(index);
            if (current.id().equals(sanctionId) && "ACTIVE".equals(current.status()) && current.revokedAt() == null) {
                sanctions.set(index, new AdminSanction(
                        current.id(), current.memberId(), "REVOKED", current.reason(), current.startsAt(),
                        current.endsAt(), current.createdBy(), adminId, revokedAt, current.createdAt()));
                return true;
            }
        }
        return false;
    }

    @Override
    public synchronized int expireDue(Instant now) {
        int changed = 0;
        for (int index = 0; index < sanctions.size(); index++) {
            var current = sanctions.get(index);
            if ("ACTIVE".equals(current.status()) && current.endsAt() != null
                    && !current.endsAt().isAfter(now)) {
                sanctions.set(index, new AdminSanction(
                        current.id(), current.memberId(), "EXPIRED", current.reason(), current.startsAt(),
                        current.endsAt(), current.createdBy(), current.revokedBy(), current.revokedAt(), current.createdAt()));
                changed++;
            }
        }
        return changed;
    }
}
