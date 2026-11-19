package com.yrootlab.onmaru.mapcollab.application;

import com.yrootlab.onmaru.mapcollab.domain.UserRoomMembership;
import com.yrootlab.onmaru.mapcollab.port.RoomRepository;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public final class ListMyMapRoomsUseCase {

    private final RoomRepository roomRepository;

    public ListMyMapRoomsUseCase(RoomRepository roomRepository) {
        this.roomRepository = Objects.requireNonNull(roomRepository, "roomRepository must not be null");
    }

    public List<UserRoomMembership> execute(UUID memberId, Instant now) {
        return roomRepository.findActiveRoomsByMemberId(memberId, now);
    }
}
