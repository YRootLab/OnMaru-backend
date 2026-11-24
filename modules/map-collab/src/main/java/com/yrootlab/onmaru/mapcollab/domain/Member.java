package com.yrootlab.onmaru.mapcollab.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public final class Member {

    private final UUID roomId;
    private final UUID memberId;
    private final String displayName;
    private final MemberRole role;
    private final Instant joinedAt;

    public Member(
            UUID roomId,
            UUID memberId,
            String displayName,
            MemberRole role,
            Instant joinedAt) {
        this.roomId = Objects.requireNonNull(roomId, "roomId must not be null");
        this.memberId = Objects.requireNonNull(memberId, "memberId must not be null");
        this.displayName = displayName != null ? displayName : "";
        this.role = Objects.requireNonNull(role, "role must not be null");
        this.joinedAt = Objects.requireNonNull(joinedAt, "joinedAt must not be null");
    }

    public Member withDisplayName(String newDisplayName) {
        return new Member(roomId, memberId, newDisplayName, role, joinedAt);
    }

    public UUID roomId() { return roomId; }
    public UUID memberId() { return memberId; }
    public String displayName() { return displayName; }
    public MemberRole role() { return role; }
    public Instant joinedAt() { return joinedAt; }
}
