package com.yrootlab.onmaru.journey.exploration;

import java.util.UUID;

public record ExplorationRunRequest(
        UUID explorationId,
        UUID runId,
        int baseVersion,
        String regionCode) {
}
