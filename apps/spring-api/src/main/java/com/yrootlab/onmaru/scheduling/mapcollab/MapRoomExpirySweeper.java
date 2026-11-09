package com.yrootlab.onmaru.scheduling.mapcollab;

import com.yrootlab.onmaru.mapcollab.domain.Room;
import com.yrootlab.onmaru.mapcollab.domain.RoomEvent;
import com.yrootlab.onmaru.mapcollab.domain.RoomEventType;
import com.yrootlab.onmaru.mapcollab.port.RoomRepository;
import com.yrootlab.onmaru.mapcollab.runtime.RoomRegistry;
import com.yrootlab.onmaru.web.mapcollab.ws.MapCollabSessionRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

@Component
public class MapRoomExpirySweeper {

    private static final Logger log = LoggerFactory.getLogger(MapRoomExpirySweeper.class);
    private static final int BATCH_SIZE = 50;

    private final RoomRepository roomRepository;
    private final RoomRegistry roomRegistry;
    private final MapCollabSessionRegistry sessionRegistry;
    private final Clock clock;

    public MapRoomExpirySweeper(
            RoomRepository roomRepository,
            RoomRegistry roomRegistry,
            MapCollabSessionRegistry sessionRegistry,
            Clock clock) {
        this.roomRepository = Objects.requireNonNull(roomRepository, "roomRepository must not be null");
        this.roomRegistry = Objects.requireNonNull(roomRegistry, "roomRegistry must not be null");
        this.sessionRegistry = Objects.requireNonNull(sessionRegistry, "sessionRegistry must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    @Scheduled(fixedDelayString = "${onmaru.mapcollab.sweeper.delay:30000}", initialDelay = 10000)
    public int sweep() {
        return sweepOnce(clock.instant());
    }

    public int sweepOnce(Instant now) {
        List<Room> expiredRooms = roomRepository.findExpiredActiveRooms(now, BATCH_SIZE);
        if (expiredRooms.isEmpty()) {
            return 0;
        }

        int closedCount = 0;
        for (Room room : expiredRooms) {
            try {
                long nextSeq = room.lastEventSeq() + 1;
                RoomEvent closeEvent = new RoomEvent(
                        room.id(),
                        nextSeq,
                        RoomEventType.ROOM_CLOSED,
                        null,
                        null,
                        null,
                        null,
                        "{}",
                        now
                );

                roomRepository.closeRoom(room.id(), now, null, closeEvent);
                roomRegistry.remove(room.id());
                sessionRegistry.closeRoomSessions(room.id(), 4010, "ROOM_CLOSED");

                log.info("Closed expired map room: roomId={}, seq={}", room.id(), nextSeq);
                closedCount++;
            } catch (Exception e) {
                log.error("Failed to close expired map room: roomId={}", room.id(), e);
            }
        }
        return closedCount;
    }
}
