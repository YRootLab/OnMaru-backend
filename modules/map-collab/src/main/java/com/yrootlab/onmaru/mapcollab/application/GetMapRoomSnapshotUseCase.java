package com.yrootlab.onmaru.mapcollab.application;

import com.yrootlab.onmaru.mapcollab.domain.Pin;
import com.yrootlab.onmaru.mapcollab.domain.Room;
import com.yrootlab.onmaru.mapcollab.domain.exception.ForbiddenException;
import com.yrootlab.onmaru.mapcollab.domain.exception.RoomClosedException;
import com.yrootlab.onmaru.mapcollab.domain.exception.RoomExpiredException;
import com.yrootlab.onmaru.mapcollab.domain.exception.RoomNotFoundException;
import com.yrootlab.onmaru.mapcollab.port.RoomRepository;
import com.yrootlab.onmaru.mapcollab.runtime.RoomActor;
import com.yrootlab.onmaru.mapcollab.runtime.RoomRegistry;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public final class GetMapRoomSnapshotUseCase {

    private final RoomRepository roomRepository;
    private final RoomRegistry roomRegistry;

    public record SnapshotResult(
            long lastSeq,
            List<Pin> pins) {
    }

    public GetMapRoomSnapshotUseCase(RoomRepository roomRepository, RoomRegistry roomRegistry) {
        this.roomRepository = Objects.requireNonNull(roomRepository, "roomRepository must not be null");
        this.roomRegistry = Objects.requireNonNull(roomRegistry, "roomRegistry must not be null");
    }

    public SnapshotResult execute(UUID roomId, UUID memberId, Instant now) {
        Room room = roomRepository.findRoomById(roomId)
                .orElseThrow(RoomNotFoundException::new);

        if (!room.isActive()) {
            throw new RoomClosedException();
        }
        if (room.isExpired(now)) {
            throw new RoomExpiredException();
        }

        if (!roomRepository.isMember(roomId, memberId)) {
            throw new ForbiddenException();
        }

        RoomActor actor = roomRegistry.getOrCreate(roomId);
        return new SnapshotResult(actor.getLastEventSeq(), actor.getActivePins());
    }
}
