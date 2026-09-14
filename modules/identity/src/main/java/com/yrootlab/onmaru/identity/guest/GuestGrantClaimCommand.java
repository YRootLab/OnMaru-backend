package com.yrootlab.onmaru.identity.guest;

import java.util.UUID;

public record GuestGrantClaimCommand(UUID memberId, UUID explorationId, String guestToken) {

    public GuestGrantClaimCommand {
        if (memberId == null) {
            throw new IllegalArgumentException("member id must not be null");
        }
        if (explorationId == null) {
            throw new IllegalArgumentException("exploration id must not be null");
        }
        if (guestToken == null || guestToken.isBlank()) {
            throw new IllegalArgumentException("guest token must not be blank");
        }
    }
}
