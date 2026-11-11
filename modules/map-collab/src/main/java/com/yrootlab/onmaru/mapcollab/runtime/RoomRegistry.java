package com.yrootlab.onmaru.mapcollab.runtime;

import com.yrootlab.onmaru.mapcollab.domain.Room;
import com.yrootlab.onmaru.mapcollab.domain.exception.RoomNotFoundException;
import com.yrootlab.onmaru.mapcollab.port.MemberProfilePort;
import com.yrootlab.onmaru.mapcollab.port.PlaceCatalogPort;
import com.yrootlab.onmaru.mapcollab.port.RoomEventPublisher;
import com.yrootlab.onmaru.mapcollab.port.RoomRepository;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class RoomRegistry {

    private final RoomRepository roomRepository;
    private final PlaceCatalogPort placeCatalogPort;
    private final MemberProfilePort memberProfilePort;
    private final RoomEventPublisher roomEventPublisher;
    private final Map<UUID, RoomActor> actors = new ConcurrentHashMap<>();

    public RoomRegistry(
            RoomRepository roomRepository,
            PlaceCatalogPort placeCatalogPort,
            MemberProfilePort memberProfilePort,
            RoomEventPublisher roomEventPublisher) {
        this.roomRepository = Objects.requireNonNull(roomRepository, "roomRepository must not be null");
        this.placeCatalogPort = Objects.requireNonNull(placeCatalogPort, "placeCatalogPort must not be null");
        this.memberProfilePort = Objects.requireNonNull(memberProfilePort, "memberProfilePort must not be null");
        this.roomEventPublisher = Objects.requireNonNull(roomEventPublisher, "roomEventPublisher must not be null");
    }

    public RoomActor getOrCreate(UUID roomId) {
        return actors.computeIfAbsent(roomId, id -> {
            Room room = roomRepository.findRoomById(id)
                    .orElseThrow(RoomNotFoundException::new);
            return RoomActor.restore(
                    id,
                    room,
                    roomRepository,
                    placeCatalogPort,
                    memberProfilePort,
                    roomEventPublisher
            );
        });
    }

    public RoomActor register(UUID roomId, RoomActor actor) {
        actors.put(roomId, actor);
        return actor;
    }

    public void remove(UUID roomId) {
        actors.remove(roomId);
    }
}
