package com.yrootlab.onmaru.security.web;

import java.util.UUID;

public final class OwnershipGuard {

    private OwnershipGuard() {
    }

    public static void requireOwner(UUID actorId, UUID ownerId) {
        if (actorId == null || ownerId == null || !actorId.equals(ownerId)) {
            throw new ResourceNotFoundException();
        }
    }
}
