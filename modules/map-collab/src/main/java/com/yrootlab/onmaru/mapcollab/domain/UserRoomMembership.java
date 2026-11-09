package com.yrootlab.onmaru.mapcollab.domain;

import java.time.Instant;
import java.util.UUID;

public record UserRoomMembership(
        UUID id,
        RoomStatus status,
        Instant expiresAt,
        MemberRole role,
        Instant joinedAt) {
}
