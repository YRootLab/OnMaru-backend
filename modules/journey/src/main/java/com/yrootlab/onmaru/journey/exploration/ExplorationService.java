package com.yrootlab.onmaru.journey.exploration;

import java.time.Clock;
import java.time.Duration;
import java.util.UUID;
import java.util.regex.Pattern;

public final class ExplorationService {

    private static final int MAX_QUERY_LENGTH = 1000;
    private static final int MAX_REGION_CODE_LENGTH = 32;
    private static final Duration RUN_BUDGET = Duration.ofSeconds(30);
    private static final Pattern REGION_CODE = Pattern.compile("^[a-z0-9]+(?:-[a-z0-9]+)*$");

    private final ExplorationStore store;
    private final ExplorationRunDispatcher runDispatcher;
    private final Clock clock;
    private final ExplorationIntakePolicy intakePolicy = new ExplorationIntakePolicy();
    private final ExplorationAccessPolicy accessPolicy;

    public ExplorationService(ExplorationStore store, ExplorationRunDispatcher runDispatcher, Clock clock) {
        this(store, runDispatcher, clock, (actor, state) -> state.owner().equals(actor));
    }

    public ExplorationService(
            ExplorationStore store,
            ExplorationRunDispatcher runDispatcher,
            Clock clock,
            ExplorationAccessPolicy accessPolicy) {
        this.store = store;
        this.runDispatcher = runDispatcher;
        this.clock = clock;
        this.accessPolicy = accessPolicy;
    }

    public ExplorationSnapshot create(ExplorationActor actor, CreateExplorationCommand command) {
        validateActor(actor);
        var query = intakePolicy.validateCreate(validateQuery(command == null ? null : command.query()));
        validateLocale(command.locale());
        var regionCode = normalizeRegion(command.regionCode());
        if (regionCode == null) {
            regionCode = intakePolicy.detectRegion(query);
        }
        var now = clock.instant();
        var explorationId = UUID.randomUUID();
        var run = newRun(regionCode, now);
        var state = new ExplorationState(explorationId, actor, 0, regionCode, run, now);
        store.create(state, UUID.randomUUID(), query, now);
        dispatchIfNeeded(state);
        return state.snapshot();
    }

    public ExplorationSnapshot get(ExplorationActor actor, UUID explorationId) {
        return ownedState(actor, explorationId).snapshot();
    }

    public ExplorationSnapshot createTurn(
            ExplorationActor actor,
            UUID explorationId,
            CreateExplorationTurnCommand command) {
        var state = ownedState(actor, explorationId);
        validateTurn(command);
        var query = intakePolicy.validateTurn(validateQuery(command.query()));
        var existing = store.findTurn(explorationId, command.clientTurnId());
        if (existing.isPresent()) {
            if (!existing.get().matches(command)) {
                throw new ExplorationTurnConflictException();
            }
            return new ExplorationSnapshot(
                    state.id(),
                    state.stateVersion(),
                    existing.get().regionCode(),
                    existing.get().run(),
                    existing.get().createdAt());
        }
        if (command.baseVersion() != state.stateVersion()) {
            throw new ExplorationVersionConflictException(state.stateVersion());
        }
        var regionCode = normalizeRegion(command.regionCode() == null ? state.regionCode() : command.regionCode());
        var now = clock.instant();
        var run = newRun(regionCode, now);
        var turn = new StoredExplorationTurn(
                command.clientTurnId(), command.baseVersion(), query, regionCode, run, now);
        var updated = new ExplorationState(
                state.id(), state.owner(), state.stateVersion(), regionCode, run, now);
        store.appendTurn(explorationId, turn);
        store.update(updated);
        dispatchIfNeeded(updated);
        return updated.snapshot();
    }

    private ExplorationState ownedState(ExplorationActor actor, UUID explorationId) {
        validateActor(actor);
        if (explorationId == null) {
            throw new ExplorationNotFoundException();
        }
        return store.find(explorationId)
                .filter(state -> accessPolicy.canAccess(actor, state))
                .orElseThrow(ExplorationNotFoundException::new);
    }

    private void validateTurn(CreateExplorationTurnCommand command) {
        if (command == null || command.clientTurnId() == null) {
            throw new ExplorationInputInvalidException("clientTurnId");
        }
        if (command.baseVersion() < 0) {
            throw new ExplorationInputInvalidException("baseVersion");
        }
    }

    private String validateQuery(String value) {
        if (value == null || value.isBlank() || value.length() > MAX_QUERY_LENGTH) {
            throw new ExplorationInputInvalidException("query");
        }
        return value.trim();
    }

    private void validateLocale(String locale) {
        if (!"ko-KR".equals(locale)) {
            throw new ExplorationInputInvalidException("locale");
        }
    }

    private String normalizeRegion(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        var normalized = value.trim();
        if (normalized.length() > MAX_REGION_CODE_LENGTH || !REGION_CODE.matcher(normalized).matches()) {
            throw new ExplorationInputInvalidException("regionCode");
        }
        return normalized;
    }

    private void validateActor(ExplorationActor actor) {
        if (actor == null) {
            throw new ExplorationNotFoundException();
        }
    }

    private ExplorationRun newRun(String regionCode, java.time.Instant now) {
        if (regionCode == null) {
            return new ExplorationRun(
                    UUID.randomUUID(),
                    ExplorationRunStatus.COMPLETED,
                    "BASELINE",
                    ExplorationRunOutcome.CLARIFICATION_REQUIRED,
                    new ExplorationClarification(
                            "REGION_MISSING",
                            "어느 지역을 둘러보고 싶으신가요?",
                            true),
                    now,
                    now,
                    now.plus(RUN_BUDGET));
        }
        return new ExplorationRun(
                UUID.randomUUID(),
                ExplorationRunStatus.QUEUED,
                "LLM",
                null,
                null,
                now,
                null,
                now.plus(RUN_BUDGET));
    }

    private void dispatchIfNeeded(ExplorationState state) {
        if (state.latestRun().status() == ExplorationRunStatus.QUEUED) {
            runDispatcher.dispatch(new ExplorationRunRequest(
                    state.id(),
                    state.latestRun().id(),
                    state.stateVersion(),
                    state.regionCode()));
        }
    }
}
