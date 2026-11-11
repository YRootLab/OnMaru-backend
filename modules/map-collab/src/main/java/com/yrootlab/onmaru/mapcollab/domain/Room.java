package com.yrootlab.onmaru.mapcollab.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public final class Room {

    private final UUID id;
    private final UUID createdBy;
    private final RoomStatus status;
    private final Instant createdAt;
    private final Instant expiresAt;
    private final Instant closedAt;
    private final UUID closedBy;
    private final long lastEventSeq;

    public Room(
            UUID id,
            UUID createdBy,
            RoomStatus status,
            Instant createdAt,
            Instant expiresAt,
            long lastEventSeq) {
        this(id, createdBy, status, createdAt, expiresAt, null, null, lastEventSeq);
    }

    public Room(
            UUID id,
            UUID createdBy,
            RoomStatus status,
            Instant createdAt,
            Instant expiresAt,
            Instant closedAt,
            UUID closedBy,
            long lastEventSeq) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.createdBy = Objects.requireNonNull(createdBy, "createdBy must not be null");
        this.status = Objects.requireNonNull(status, "status must not be null");
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
        this.expiresAt = Objects.requireNonNull(expiresAt, "expiresAt must not be null");
        this.closedAt = closedAt;
        this.closedBy = closedBy;
        this.lastEventSeq = lastEventSeq;
    }

    public boolean isActive() {
        return status == RoomStatus.ACTIVE;
    }

    public boolean isExpired(Instant now) {
        return !now.isBefore(expiresAt);
    }

    public Room close(Instant closedAt, UUID closedBy) {
        return new Room(id, createdBy, RoomStatus.CLOSED, createdAt, expiresAt, closedAt, closedBy, lastEventSeq);
    }

    public Room withLastEventSeq(long newLastEventSeq) {
        return new Room(id, createdBy, status, createdAt, expiresAt, closedAt, closedBy, newLastEventSeq);
    }

    public UUID id() { return id; }
    public UUID createdBy() { return createdBy; }
    public RoomStatus status() { return status; }
    public Instant createdAt() { return createdAt; }
    public Instant expiresAt() { return expiresAt; }
    public Instant closedAt() { return closedAt; }
    public UUID closedBy() { return closedBy; }
    public long lastEventSeq() { return lastEventSeq; }
}
