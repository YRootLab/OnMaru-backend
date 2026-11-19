package com.yrootlab.onmaru.mapcollab.domain.exception;

public final class RoomClosedException extends MapCollabException {
    public RoomClosedException() {
        super("ROOM_CLOSED", 410, "room closed");
    }
}
