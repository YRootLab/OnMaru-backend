package com.yrootlab.onmaru.journey.exploration;

import java.time.Instant;
import java.util.UUID;

public record StoredExplorationTurn(
        UUID clientTurnId,
        int baseVersion,
        String query,
        String regionCode,
        String clarificationId,
        ExplorationRun run,
        Instant createdAt) {

    boolean matches(CreateExplorationTurnCommand command) {
        return baseVersion == command.baseVersion()
                && query.equals(command.query().trim())
                && java.util.Objects.equals(regionCode, normalize(command.regionCode()))
                && java.util.Objects.equals(clarificationId, command.clarificationId());
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
