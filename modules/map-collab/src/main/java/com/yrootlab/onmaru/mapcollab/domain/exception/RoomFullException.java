package com.yrootlab.onmaru.mapcollab.domain.exception;

import java.util.Map;

public final class RoomFullException extends MapCollabException {
    public RoomFullException() {
        super("ROOM_FULL", 409, "room is full", Map.of("limit", 20));
    }
}
