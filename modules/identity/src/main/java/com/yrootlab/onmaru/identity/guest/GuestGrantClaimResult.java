package com.yrootlab.onmaru.identity.guest;

import java.time.Instant;
import java.util.UUID;

public record GuestGrantClaimResult(UUID memberId, UUID explorationId, Instant expiresAt) {
}
