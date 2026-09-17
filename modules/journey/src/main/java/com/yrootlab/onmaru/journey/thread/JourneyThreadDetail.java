package com.yrootlab.onmaru.journey.thread;

import java.util.List;

public record JourneyThreadDetail(
        JourneyThread thread,
        List<JourneyTurnMemory> turnHistory,
        String explorationSnapshotUrl) {
}
