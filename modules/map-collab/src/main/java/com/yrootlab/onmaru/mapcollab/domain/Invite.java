package com.yrootlab.onmaru.mapcollab.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public final class Invite {

    private final UUID id;
    private final UUID roomId;
    private final String tokenHash;
    private final UUID createdBy;
    private final Instant createdAt;
    private final Instant expiresAt;
    private final Instant revokedAt;

    public Invite(
            UUID id,
            UUID roomId,
            String tokenHash,
            UUID createdBy,
            Instant createdAt,
            Instant expiresAt) {
        this(id, roomId, tokenHash, createdBy, createdAt, expiresAt, null);
    }

    public Invite(
            UUID id,
            UUID roomId,
            String tokenHash,
            UUID createdBy,
            Instant createdAt,
            Instant expiresAt,
            Instant revokedAt) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.roomId = Objects.requireNonNull(roomId, "roomId must not be null");
        this.tokenHash = Objects.requireNonNull(tokenHash, "tokenHash must not be null");
        this.createdBy = Objects.requireNonNull(createdBy, "createdBy must not be null");
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
        this.expiresAt = Objects.requireNonNull(expiresAt, "expiresAt must not be null");
        this.revokedAt = revokedAt;
    }

    public boolean isRevoked() {
        return revokedAt != null;
    }

    public boolean isExpired(Instant now) {
        return !now.isBefore(expiresAt);
    }

    public boolean isValid(Instant now) {
        return !isRevoked() && !isExpired(now);
    }

    public Invite revoke(Instant revokedAt) {
        return new Invite(id, roomId, tokenHash, createdBy, createdAt, expiresAt, revokedAt);
    }

    public UUID id() { return id; }
    public UUID roomId() { return roomId; }
    public String tokenHash() { return tokenHash; }
    public UUID createdBy() { return createdBy; }
    public Instant createdAt() { return createdAt; }
    public Instant expiresAt() { return expiresAt; }
    public Instant revokedAt() { return revokedAt; }
}
