package com.yrootlab.onmaru.journey.exploration;

import java.time.Clock;
import java.time.Duration;
import java.util.Set;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

import com.yrootlab.onmaru.journey.events.JourneyRunEventSink;
import com.yrootlab.onmaru.journey.actions.JourneyAction;
import com.yrootlab.onmaru.journey.actions.JourneyActionResult;
import com.yrootlab.onmaru.journey.actions.JourneyActionService;
import com.yrootlab.onmaru.journey.actions.JourneyActionState;

public final class ExplorationService {

    private static final int MAX_QUERY_LENGTH = 1000;
    private static final int MAX_REGION_CODE_LENGTH = 32;
    private static final Duration RUN_BUDGET = Duration.ofSeconds(20);
    private static final Pattern REGION_CODE = Pattern.compile("^[a-z0-9]+(?:-[a-z0-9]+)*$");
    private static final Set<String> RUN_STAGES = Set.of("INTERPRETING", "RETRIEVING", "VALIDATING", "PERSISTING");

    private final ExplorationStore store;
    private final ExplorationRunDispatcher runDispatcher;
    private final Clock clock;
    private final ExplorationIntakePolicy intakePolicy = new ExplorationIntakePolicy();
    private final ExplorationAccessPolicy accessPolicy;
    private final JourneyRunEventSink eventSink;
    private final JourneyActionService actionService;
    private final Map<UUID, JourneyActionState> actionStates = new HashMap<>();

    public ExplorationService(ExplorationStore store, ExplorationRunDispatcher runDispatcher, Clock clock) {
        this(store, runDispatcher, clock, (actor, state) -> state.owner().equals(actor));
    }

    public ExplorationService(
            ExplorationStore store,
            ExplorationRunDispatcher runDispatcher,
            Clock clock,
            ExplorationAccessPolicy accessPolicy) {
        this(store, runDispatcher, clock, accessPolicy, JourneyRunEventSink.noop());
    }

    public ExplorationService(
            ExplorationStore store,
            ExplorationRunDispatcher runDispatcher,
            Clock clock,
            ExplorationAccessPolicy accessPolicy,
            JourneyRunEventSink eventSink) {
        this(store, runDispatcher, clock, accessPolicy, eventSink, null);
    }

    public ExplorationService(
            ExplorationStore store,
            ExplorationRunDispatcher runDispatcher,
            Clock clock,
            ExplorationAccessPolicy accessPolicy,
            JourneyRunEventSink eventSink,
            JourneyActionService actionService) {
        this.store = store;
        this.runDispatcher = runDispatcher;
        this.clock = clock;
        this.accessPolicy = accessPolicy;
        this.eventSink = eventSink;
        this.actionService = actionService;
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
        publish(run);
        dispatchIfNeeded(state);
        return state.snapshot();
    }

    public boolean willCreateAiRun(ExplorationActor actor, CreateExplorationCommand command) {
        validateActor(actor);
        var query = intakePolicy.validateCreate(validateQuery(command == null ? null : command.query()));
        validateLocale(command.locale());
        var regionCode = normalizeRegion(command.regionCode());
        if (regionCode == null) {
            regionCode = intakePolicy.detectRegion(query);
        }
        return regionCode != null;
    }

    public ExplorationSnapshot get(ExplorationActor actor, UUID explorationId) {
        return ownedState(actor, explorationId).snapshot();
    }

    public synchronized JourneyActionResult applyAction(
            ExplorationActor actor, UUID explorationId, int baseVersion, JourneyAction action) {
        if (actionService == null) {
            throw new IllegalStateException("journey action service is unavailable");
        }
        var state = ownedState(actor, explorationId);
        if (state.latestRun().isActive()) {
            throw new ExplorationActiveRunException();
        }
        var current = actionStates.computeIfAbsent(explorationId, ignored -> JourneyActionState.empty(state.stateVersion()));
        var result = actionService.apply(current, baseVersion, action);
        if (result.changed()) {
            actionStates.put(explorationId, result.state());
            store.update(new ExplorationState(
                    state.id(), state.owner(), result.state().stateVersion(), state.regionCode(), state.latestRun(), clock.instant()));
        }
        return result;
    }

    public synchronized JourneyActionState actionState(ExplorationActor actor, UUID explorationId) {
        var state = ownedState(actor, explorationId);
        return actionStates.computeIfAbsent(explorationId, ignored -> JourneyActionState.empty(state.stateVersion()));
    }

    public ExplorationRun getRun(ExplorationActor actor, UUID explorationId, UUID runId) {
        var state = ownedState(actor, explorationId);
        var run = state.latestRun();
        if (run == null || !run.id().equals(runId)) {
            throw new ExplorationNotFoundException();
        }
        return run;
    }

    public ExplorationSnapshot createTurn(
            ExplorationActor actor,
            UUID explorationId,
            CreateExplorationTurnCommand command) {
        var state = ownedState(actor, explorationId);
        validateTurn(command);
        var validatedQuery = validateQuery(command.query());
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
        validateClarificationAnswer(state, command.clarificationId());
        if (state.latestRun().isActive()) {
            throw new ExplorationActiveRunException();
        }
        var query = intakePolicy.validateTurn(validatedQuery);
        var regionCode = normalizeRegion(command.regionCode() == null ? state.regionCode() : command.regionCode());
        var now = clock.instant();
        var run = newRun(regionCode, now);
        var turn = new StoredExplorationTurn(
                command.clientTurnId(), command.baseVersion(), query, regionCode, command.clarificationId(), run, now);
        var updated = new ExplorationState(
                state.id(), state.owner(), state.stateVersion(), regionCode, run, now);
        store.appendTurn(explorationId, turn);
        store.update(updated);
        publish(run);
        dispatchIfNeeded(updated);
        return updated.snapshot();
    }

    public boolean willCreateTurnAiRun(
            ExplorationActor actor,
            UUID explorationId,
            CreateExplorationTurnCommand command) {
        var state = ownedState(actor, explorationId);
        validateTurn(command);
        validateQuery(command.query());
        var existing = store.findTurn(explorationId, command.clientTurnId());
        if (existing.isPresent()) {
            if (!existing.get().matches(command)) {
                throw new ExplorationTurnConflictException();
            }
            return false;
        }
        if (command.baseVersion() != state.stateVersion()) {
            throw new ExplorationVersionConflictException(state.stateVersion());
        }
        validateClarificationAnswer(state, command.clarificationId());
        if (state.latestRun().isActive()) {
            throw new ExplorationActiveRunException();
        }
        var query = intakePolicy.validateTurn(validateQuery(command.query()));
        var regionCode = normalizeRegion(command.regionCode() == null ? state.regionCode() : command.regionCode());
        if (regionCode == null) {
            regionCode = intakePolicy.detectRegion(query);
        }
        return regionCode != null;
    }

    public ExplorationRun claimRun(UUID explorationId, UUID runId, String stage) {
        validateRunCommand(explorationId, runId);
        validateStage(stage);
        var run = store.claimRun(explorationId, runId, clock.instant(), stage);
        publish(run);
        return run;
    }

    public ExplorationRun completeRun(UUID explorationId, UUID runId, ExplorationRunOutcome outcome) {
        validateRunCommand(explorationId, runId);
        if (outcome == null) {
            throw new ExplorationInputInvalidException("outcome");
        }
        var run = store.finishRun(explorationId, runId, ExplorationRunStatus.COMPLETED, outcome, clock.instant());
        publish(run);
        return run;
    }

    public ExplorationRun cancelRun(UUID explorationId, UUID runId) {
        validateRunCommand(explorationId, runId);
        var run = store.finishRun(explorationId, runId, ExplorationRunStatus.CANCELLED, null, clock.instant());
        publish(run);
        return run;
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

    private void validateRunCommand(UUID explorationId, UUID runId) {
        if (explorationId == null || runId == null) {
            throw new ExplorationNotFoundException();
        }
    }

    private void validateStage(String stage) {
        if (stage == null || !RUN_STAGES.contains(stage)) {
            throw new ExplorationInputInvalidException("stage");
        }
    }

    private void validateClarificationAnswer(ExplorationState state, String clarificationId) {
        if (clarificationId == null) {
            return;
        }
        var run = state.latestRun();
        var clarification = run.clarification();
        if (run.status() != ExplorationRunStatus.COMPLETED
                || run.outcome() != ExplorationRunOutcome.CLARIFICATION_REQUIRED
                || clarification == null
                || !clarification.id().equals(clarificationId)) {
            throw new ExplorationVersionConflictException(state.stateVersion());
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
                            "region",
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

    private void publish(ExplorationRun run) {
        if (run.isTerminal()) {
            eventSink.terminal(run.id(), run.status().name(), run.outcome() == null ? null : run.outcome().name());
            return;
        }
        eventSink.stage(run.id(), run.status().name(), run.stage());
    }
}
