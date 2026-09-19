package com.yrootlab.onmaru.architecture.fixture.module.clean.app.bridge;

import com.yrootlab.onmaru.architecture.fixture.module.clean.discovery.api.DiscoverySnapshotApi;
import com.yrootlab.onmaru.architecture.fixture.module.clean.journey.port.ExplorationSnapshotPort;

class DiscoverySnapshotBridge implements ExplorationSnapshotPort {

    private final DiscoverySnapshotApi discoverySnapshotApi;

    DiscoverySnapshotBridge(DiscoverySnapshotApi discoverySnapshotApi) {
        this.discoverySnapshotApi = discoverySnapshotApi;
    }
}
