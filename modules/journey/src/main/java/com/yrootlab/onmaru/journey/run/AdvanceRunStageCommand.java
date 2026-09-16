package com.yrootlab.onmaru.journey.run;

import java.util.UUID;

public record AdvanceRunStageCommand(
        UUID commandKey,
        String actorKey,
        UUID runId,
        String requestHash,
        int expectedGeneration,
        JourneyRunStage expectedStage,
        JourneyRunStage nextStage) {
}
