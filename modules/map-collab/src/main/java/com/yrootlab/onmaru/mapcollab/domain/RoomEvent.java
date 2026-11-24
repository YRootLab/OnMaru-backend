package com.yrootlab.onmaru.mapcollab.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public final class RoomEvent {

    private final UUID roomId;
    private final long seq;
    private final RoomEventType eventType;
    private final UUID actorMemberId;
    private final UUID sessionId;
    private final UUID clientOpId;
    private final UUID pinId;
    private final String payload;
    private final Instant createdAt;

    public RoomEvent(
            UUID roomId,
            long seq,
            RoomEventType eventType,
            UUID actorMemberId,
            UUID sessionId,
            UUID clientOpId,
            UUID pinId,
            String payload,
            Instant createdAt) {
        this.roomId = Objects.requireNonNull(roomId, "roomId must not be null");
        this.seq = seq;
        this.eventType = Objects.requireNonNull(eventType, "eventType must not be null");
        this.actorMemberId = actorMemberId;
        this.sessionId = sessionId;
        this.clientOpId = clientOpId;
        this.pinId = pinId;
        this.payload = payload != null ? payload : "{}";
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
    }

    public UUID roomId() { return roomId; }
    public long seq() { return seq; }
    public RoomEventType eventType() { return eventType; }
    public UUID actorMemberId() { return actorMemberId; }
    public UUID sessionId() { return sessionId; }
    public UUID clientOpId() { return clientOpId; }
    public UUID pinId() { return pinId; }
    public String payload() { return payload; }
    public Instant createdAt() { return createdAt; }
}
