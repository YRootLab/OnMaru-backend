package com.yrootlab.onmaru.web.exploration;

import com.yrootlab.onmaru.journey.exploration.ExplorationSnapshot;

import java.util.UUID;

record RunAcceptedResponse(
        String schemaVersion,
        UUID explorationId,
        UUID runId,
        int stateVersion,
        String runUrl,
        String eventsUrl,
        String snapshotUrl) {

    static RunAcceptedResponse from(ExplorationSnapshot snapshot) {
        var base = "/api/v1/explorations/" + snapshot.explorationId();
        var runUrl = base + "/runs/" + snapshot.run().id();
        return new RunAcceptedResponse(
                "1.2",
                snapshot.explorationId(),
                snapshot.run().id(),
                snapshot.stateVersion(),
                runUrl,
                runUrl + "/events",
                base);
    }
}
