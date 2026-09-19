package com.yrootlab.onmaru.identity.oauth;

import java.time.Instant;
import java.util.UUID;

public record SessionRecord(
        String tokenHash,
        UUID memberId,
        Instant createdAt,
        Instant lastSeenAt,
        Instant absoluteExpiresAt) {
}
