package com.yrootlab.onmaru.journey.savedjourney;

import com.yrootlab.onmaru.journey.exploration.ExplorationActor;

import java.util.UUID;

public record CreateSavedJourneyCommand(
        UUID memberId,
        ExplorationActor actor,
        UUID explorationId,
        int baseVersion,
        String title) {
}
