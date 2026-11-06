package com.yrootlab.onmaru.journey.exploration;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class InMemoryExplorationStore implements ExplorationStore {

    private final Map<UUID, ExplorationState> explorations = new HashMap<>();
    private final Map<UUID, Map<UUID, StoredExplorationTurn>> turns = new HashMap<>();
    private final Set<UUID> deletedExplorationIds = ConcurrentHashMap.newKeySet();

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
        if (deletedExplorationIds.contains(explorationId)) {
            return Optional.empty();
        }
        return Optional.ofNullable(explorations.get(explorationId));
    }

    @Override
    public synchronized Optional<StoredExplorationTurn> findTurn(UUID explorationId, UUID clientTurnId) {
        if (deletedExplorationIds.contains(explorationId)) {
            return Optional.empty();
        }
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

    @Override
    public synchronized ExplorationPage list(ExplorationActor actor, int limit, String cursor, Instant now) {
        var items = new ArrayList<ExplorationSummary>();
        for (var state : explorations.values()) {
            if (deletedExplorationIds.contains(state.id())) {
                continue;
            }
            if (!state.owner().equals(actor)) {
                continue;
            }
            var title = state.regionCode() != null ? state.regionCode() + " 여행" : "여정 탐색";
            var lastQuery = turns.getOrDefault(state.id(), Map.of()).values().stream()
                    .max(Comparator.comparing(StoredExplorationTurn::createdAt))
                    .map(StoredExplorationTurn::query)
                    .orElse(null);
            var latestStatus = state.latestRun() != null ? state.latestRun().status() : null;
            items.add(new ExplorationSummary(
                    state.id(),
                    title,
                    lastQuery,
                    latestStatus,
                    0,
                    0,
                    state.updatedAt()));
        }
        items.sort(Comparator.comparing(ExplorationSummary::updatedAt).reversed());
        return new ExplorationPage(items, items.size(), null, false);
    }

    @Override
    public synchronized ExplorationTurnPage listTurns(UUID explorationId, int limit, String cursor) {
        if (deletedExplorationIds.contains(explorationId)) {
            return new ExplorationTurnPage(List.of(), null, false);
        }
        var turnMap = turns.getOrDefault(explorationId, Map.of());
        var turnList = new ArrayList<>(turnMap.values());
        turnList.sort(Comparator.comparing(StoredExplorationTurn::createdAt).reversed());

        var items = turnList.stream()
                .map(t -> new ExplorationTurn(
                        t.clientTurnId(),
                        t.query(),
                        t.createdAt(),
                        t.run() != null ? t.run().id() : null,
                        t.run() != null && t.run().isTerminal()
                                ? TurnResponse.completed(
                                        t.run().outcome() != null ? t.run().outcome().name() : null,
                                        null, null, null, null)
                                : TurnResponse.pending()))
                .toList();
        return new ExplorationTurnPage(items, null, false);
    }

    @Override
    public synchronized boolean delete(ExplorationActor actor, UUID explorationId, Instant deletedAt) {
        var state = explorations.get(explorationId);
        if (state == null) {
            return false;
        }
        if (!state.owner().equals(actor)) {
            return false;
        }
        deletedExplorationIds.add(explorationId);
        return true;
    }

    @Override
    public synchronized void deleteExpiredGuests(Instant now) {
        for (var state : new ArrayList<>(explorations.values())) {
            if (state.owner().type() == ExplorationActorType.GUEST) {
                // 24 hours retention
                if (state.updatedAt().plusSeconds(24 * 3600).isBefore(now)) {
                    deletedExplorationIds.add(state.id());
                }
            }
        }
    }

    public synchronized int explorationCount() {
        return (int) explorations.keySet().stream().filter(id -> !deletedExplorationIds.contains(id)).count();
    }

    public synchronized int turnCount(UUID explorationId) {
        if (deletedExplorationIds.contains(explorationId)) {
            return 0;
        }
        return turns.getOrDefault(explorationId, Map.of()).size();
    }

    public synchronized int totalTurnCount() {
        return turns.entrySet().stream()
                .filter(e -> !deletedExplorationIds.contains(e.getKey()))
                .mapToInt(e -> e.getValue().size())
                .sum();
    }

    public synchronized Optional<String> storedQuery(UUID explorationId, UUID clientTurnId) {
        return findTurn(explorationId, clientTurnId).map(StoredExplorationTurn::query);
    }

    public synchronized void clear() {
        explorations.clear();
        turns.clear();
        deletedExplorationIds.clear();
    }

    private ExplorationState requireState(UUID explorationId) {
        var state = explorations.get(explorationId);
        if (state == null || deletedExplorationIds.contains(explorationId)) {
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
