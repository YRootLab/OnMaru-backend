package com.yrootlab.onmaru.journey.exploration;

import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public final class InMemoryExplorationStore implements ExplorationStore {

    private final Map<UUID, ExplorationState> explorations = new HashMap<>();
    private final Map<UUID, Map<UUID, StoredExplorationTurn>> turns = new HashMap<>();

    @Override
    public synchronized void create(ExplorationState state, UUID initialTurnId, String query, Instant createdAt) {
        explorations.put(state.id(), state);
        var initialTurn = new StoredExplorationTurn(
                initialTurnId,
                state.stateVersion(),
                query,
                state.regionCode(),
                null,
                state.latestRun(),
                createdAt);
        turns.computeIfAbsent(state.id(), ignored -> new LinkedHashMap<>()).put(initialTurnId, initialTurn);
    }

    @Override
    public synchronized Optional<ExplorationState> find(UUID explorationId) {
        return Optional.ofNullable(explorations.get(explorationId));
    }

    @Override
    public synchronized Optional<StoredExplorationTurn> findTurn(UUID explorationId, UUID clientTurnId) {
        return Optional.ofNullable(turns.getOrDefault(explorationId, Map.of()).get(clientTurnId));
    }

    @Override
    public synchronized void appendTurn(UUID explorationId, StoredExplorationTurn turn) {
        turns.computeIfAbsent(explorationId, ignored -> new LinkedHashMap<>()).put(turn.clientTurnId(), turn);
    }

    @Override
    public synchronized void update(ExplorationState state) {
        explorations.put(state.id(), state);
    }

    @Override
    public synchronized ExplorationRun claimRun(UUID explorationId, UUID runId, Instant startedAt, String stage) {
        var state = requireState(explorationId);
        var run = requireLatestRun(state, runId);
        if (run.status() != ExplorationRunStatus.QUEUED) {
            return run;
        }
        var claimed = new ExplorationRun(
                run.id(),
                ExplorationRunStatus.RUNNING,
                run.engine(),
                stage,
                null,
                null,
                run.createdAt(),
                startedAt,
                run.deadlineAt());
        updateLatestRun(state, claimed, startedAt);
        return claimed;
    }

    @Override
    public synchronized ExplorationRun finishRun(
            UUID explorationId,
            UUID runId,
            ExplorationRunStatus terminalStatus,
            ExplorationRunOutcome outcome,
            Instant finishedAt) {
        var state = requireState(explorationId);
        var run = requireLatestRun(state, runId);
        if (run.isTerminal()) {
            return run;
        }
        var finished = new ExplorationRun(
                run.id(),
                terminalStatus,
                run.engine(),
                null,
                outcome,
                null,
                run.createdAt(),
                run.startedAt(),
                run.deadlineAt());
        updateLatestRun(state, finished, finishedAt);
        return finished;
    }

    public synchronized int explorationCount() {
        return explorations.size();
    }

    public synchronized int turnCount(UUID explorationId) {
        return turns.getOrDefault(explorationId, Map.of()).size();
    }

    public synchronized int totalTurnCount() {
        return turns.values().stream().mapToInt(Map::size).sum();
    }

    public synchronized Optional<String> storedQuery(UUID explorationId, UUID clientTurnId) {
        return findTurn(explorationId, clientTurnId).map(StoredExplorationTurn::query);
    }

    public synchronized void clear() {
        explorations.clear();
        turns.clear();
    }

    private ExplorationState requireState(UUID explorationId) {
        var state = explorations.get(explorationId);
        if (state == null) {
            throw new ExplorationNotFoundException();
        }
        return state;
    }

    private ExplorationRun requireLatestRun(ExplorationState state, UUID runId) {
        var run = state.latestRun();
        if (run == null || !run.id().equals(runId)) {
            throw new ExplorationNotFoundException();
        }
        return run;
    }

    private void updateLatestRun(ExplorationState state, ExplorationRun run, Instant updatedAt) {
        explorations.put(state.id(), new ExplorationState(
                state.id(),
                state.owner(),
                state.stateVersion(),
                state.regionCode(),
                run,
                updatedAt));
    }
}
