package com.yrootlab.onmaru.journey.exploration;

import java.time.Instant;
import java.util.UUID;

public record StoredExplorationTurn(
        UUID clientTurnId,
        int baseVersion,
        String query,
        String regionCode,
        ExplorationRun run,
        Instant createdAt) {

    boolean matches(CreateExplorationTurnCommand command) {
        return baseVersion == command.baseVersion()
                && query.equals(command.query().trim())
                && java.util.Objects.equals(regionCode, normalize(command.regionCode()));
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
