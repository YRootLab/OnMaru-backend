package com.yrootlab.onmaru.web.exploration;

import com.yrootlab.onmaru.journey.exploration.ExplorationClarification;
import com.yrootlab.onmaru.journey.exploration.ExplorationRun;
import com.yrootlab.onmaru.journey.exploration.ExplorationSnapshot;
import com.yrootlab.onmaru.journey.actions.JourneyActionState;

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
        List<Object> unavailableRefs,
        Map<String, Object> execution,
        RunResponse latestRun,
        Object pendingProposal,
        List<Object> recentHistory,
        Instant updatedAt) {

    static ExplorationResponse from(ExplorationSnapshot snapshot) {
        return from(snapshot, ExplorationSnapshotHydrator.HydratedSnapshot.empty(snapshot));
    }

    static ExplorationResponse from(
            ExplorationSnapshot snapshot,
            ExplorationSnapshotHydrator.HydratedSnapshot hydrated) {
        return new ExplorationResponse(
                "1.2",
                snapshot.explorationId(),
                snapshot.stateVersion(),
                hydrated.board(),
                List.of(),
                List.of(),
                hydrated.unavailableRefs(),
                hydrated.execution(),
                RunResponse.from(snapshot),
                null,
                List.of(),
                snapshot.updatedAt());
    }

    static ExplorationResponse from(
            ExplorationSnapshot snapshot,
            ExplorationSnapshotHydrator.HydratedSnapshot hydrated,
            JourneyActionState actions) {
        return new ExplorationResponse(
                "1.2", snapshot.explorationId(), snapshot.stateVersion(), hydrated.board(),
                List.copyOf(actions.pinnedRefs().stream().map(ref -> (Object) Map.of("type", ref.type(), "id", ref.id())).toList()),
                List.copyOf(actions.excludedRefs().stream().map(ref -> (Object) Map.of("type", ref.type(), "id", ref.id())).toList()), hydrated.unavailableRefs(),
                hydrated.execution(), RunResponse.from(snapshot), null, List.of(), snapshot.updatedAt());
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
            return from(snapshot.run());
        }

        static RunResponse from(ExplorationRun run) {
            return new RunResponse(
                    "1.2",
                    run.id(),
                    run.status().name(),
                    run.engine(),
                    null,
                    run.stage(),
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
