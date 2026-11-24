package com.yrootlab.onmaru.mapcollab.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public final class Pin {

    private final UUID pinId;
    private final UUID roomId;
    private final String placeId;
    private final String placeName;
    private final double lat;
    private final double lng;
    private final UUID addedBy;
    private final Instant addedAt;
    private final long order;
    private final UUID removedBy;
    private final Instant removedAt;
    private final Long removedSeq;

    public Pin(
            UUID pinId,
            UUID roomId,
            String placeId,
            String placeName,
            double lat,
            double lng,
            UUID addedBy,
            Instant addedAt,
            long order) {
        this(pinId, roomId, placeId, placeName, lat, lng, addedBy, addedAt, order, null, null, null);
    }

    public Pin(
            UUID pinId,
            UUID roomId,
            String placeId,
            String placeName,
            double lat,
            double lng,
            UUID addedBy,
            Instant addedAt,
            long order,
            UUID removedBy,
            Instant removedAt,
            Long removedSeq) {
        this.pinId = Objects.requireNonNull(pinId, "pinId must not be null");
        this.roomId = Objects.requireNonNull(roomId, "roomId must not be null");
        this.placeId = Objects.requireNonNull(placeId, "placeId must not be null");
        this.placeName = Objects.requireNonNull(placeName, "placeName must not be null");
        this.lat = lat;
        this.lng = lng;
        this.addedBy = Objects.requireNonNull(addedBy, "addedBy must not be null");
        this.addedAt = Objects.requireNonNull(addedAt, "addedAt must not be null");
        this.order = order;
        this.removedBy = removedBy;
        this.removedAt = removedAt;
        this.removedSeq = removedSeq;
    }

    public boolean isActive() {
        return removedAt == null;
    }

    public Pin remove(UUID removedBy, Instant removedAt, long removedSeq) {
        if (!isActive()) {
            throw new IllegalStateException("Pin is already removed");
        }
        return new Pin(
                this.pinId,
                this.roomId,
                this.placeId,
                this.placeName,
                this.lat,
                this.lng,
                this.addedBy,
                this.addedAt,
                this.order,
                removedBy,
                removedAt,
                removedSeq
        );
    }

    public UUID pinId() { return pinId; }
    public UUID roomId() { return roomId; }
    public String placeId() { return placeId; }
    public String placeName() { return placeName; }
    public double lat() { return lat; }
    public double lng() { return lng; }
    public UUID addedBy() { return addedBy; }
    public Instant addedAt() { return addedAt; }
    public long order() { return order; }
    public UUID removedBy() { return removedBy; }
    public Instant removedAt() { return removedAt; }
    public Long removedSeq() { return removedSeq; }
}
