package com.yrootlab.onmaru.mapcollab.domain.exception;

import java.util.Map;

public final class RoomNotFoundException extends MapCollabException {
    public RoomNotFoundException() {
        super("ROOM_NOT_FOUND", 404, "room not found");
    }
}
