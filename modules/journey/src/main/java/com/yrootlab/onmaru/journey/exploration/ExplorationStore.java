package com.yrootlab.onmaru.journey.exploration;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface ExplorationStore {

    void create(ExplorationState state, UUID initialTurnId, String query, Instant createdAt);

    Optional<ExplorationState> find(UUID explorationId);

    Optional<StoredExplorationTurn> findTurn(UUID explorationId, UUID clientTurnId);

    void appendTurn(UUID explorationId, StoredExplorationTurn turn);

    void update(ExplorationState state);

    ExplorationRun claimRun(UUID explorationId, UUID runId, Instant startedAt, String stage);

    ExplorationRun finishRun(
            UUID explorationId,
            UUID runId,
            ExplorationRunStatus terminalStatus,
            ExplorationRunOutcome outcome,
            Instant finishedAt);
}
