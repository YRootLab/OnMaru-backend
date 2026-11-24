package com.yrootlab.onmaru.mapcollab.domain.exception;

public final class RoomExpiredException extends MapCollabException {
    public RoomExpiredException() {
        super("ROOM_EXPIRED", 410, "room expired");
    }
}
