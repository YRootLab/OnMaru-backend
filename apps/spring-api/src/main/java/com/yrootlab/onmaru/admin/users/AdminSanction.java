package com.yrootlab.onmaru.admin.users;

import java.time.Instant;
import java.util.UUID;

public record AdminSanction(
        UUID id,
        UUID memberId,
        String status,
        String reason,
        Instant startsAt,
        Instant endsAt,
        UUID createdBy,
        UUID revokedBy,
        Instant revokedAt,
        Instant createdAt) {
}
