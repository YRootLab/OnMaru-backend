package com.yrootlab.onmaru.journey.run;

import java.util.UUID;

public record RunCommandReceipt(
        UUID runId,
        JourneyRunStatus status,
        JourneyRunStage stage,
        String outcome,
        int generation) {
}
