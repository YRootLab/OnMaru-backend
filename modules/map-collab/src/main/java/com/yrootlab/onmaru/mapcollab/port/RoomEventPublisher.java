package com.yrootlab.onmaru.mapcollab.port;

import com.yrootlab.onmaru.mapcollab.domain.Pin;
import com.yrootlab.onmaru.mapcollab.domain.RoomEvent;

import java.util.UUID;

public interface RoomEventPublisher {

    void publishEvent(RoomEvent event, Pin pin, String actorDisplayName);

    void publishPresence(UUID roomId, UUID memberId, boolean online);

    void closeRoomSessions(UUID roomId, int closeCode, String reason);
}
