package com.yrootlab.onmaru.journey.exploration;

import java.util.UUID;

public record CreateExplorationTurnCommand(
        UUID clientTurnId,
        int baseVersion,
        String query,
        String regionCode,
        String clarificationId) {
}
