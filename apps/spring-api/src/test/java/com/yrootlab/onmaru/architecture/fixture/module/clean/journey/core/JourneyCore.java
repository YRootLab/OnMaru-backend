package com.yrootlab.onmaru.architecture.fixture.module.clean.journey.core;

import com.yrootlab.onmaru.architecture.fixture.module.clean.journey.port.ExplorationSnapshotPort;

class JourneyCore {

    private final ExplorationSnapshotPort explorationSnapshotPort;

    JourneyCore(ExplorationSnapshotPort explorationSnapshotPort) {
        this.explorationSnapshotPort = explorationSnapshotPort;
    }
}
