package com.yrootlab.onmaru.web.exploration;

import com.yrootlab.onmaru.journey.exploration.ExplorationClarification;
import com.yrootlab.onmaru.journey.exploration.ExplorationSnapshot;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

record ExplorationResponse(
        String schemaVersion,
        UUID explorationId,
        int stateVersion,
        Object board,
        List<Object> pinnedRefs,
        List<Object> excludedRefs,
        Map<String, Object> execution,
        RunResponse latestRun,
        Object pendingProposal,
        List<Object> recentHistory,
        Instant updatedAt) {

    static ExplorationResponse from(ExplorationSnapshot snapshot) {
        return new ExplorationResponse(
                "1.2",
                snapshot.explorationId(),
                snapshot.stateVersion(),
                null,
                List.of(),
                List.of(),
                Map.of(
                        "dataMode", "LIVE",
                        "engine", snapshot.run().engine(),
                        "rankingVersion", "pending",
                        "datasetRevision", "pending"),
                RunResponse.from(snapshot),
                null,
                List.of(),
                snapshot.updatedAt());
    }

    record RunResponse(
            String schemaVersion,
            UUID runId,
            String status,
            String engine,
            String degradedReason,
            String stage,
            String outcome,
            ClarificationResponse clarification,
            int retryAfterMs,
            Instant createdAt,
            Instant startedAt,
            Instant deadlineAt,
            Object error) {

        static RunResponse from(ExplorationSnapshot snapshot) {
            var run = snapshot.run();
            return new RunResponse(
                    "1.2",
                    run.id(),
                    run.status().name(),
                    run.engine(),
                    null,
                    null,
                    run.outcome() == null ? null : run.outcome().name(),
                    ClarificationResponse.from(run.clarification()),
                    0,
                    run.createdAt(),
                    run.startedAt(),
                    run.deadlineAt(),
                    null);
        }
    }

    record ClarificationResponse(
            String id,
            String reason,
            String question,
            List<Object> choices,
            boolean allowFreeText) {

        static ClarificationResponse from(ExplorationClarification clarification) {
            if (clarification == null) {
                return null;
            }
            return new ClarificationResponse(
                    clarification.id(),
                    clarification.reason(),
                    clarification.question(),
                    List.of(),
                    clarification.allowFreeText());
        }
    }
}
