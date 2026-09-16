package com.yrootlab.onmaru.journey.exploration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExplorationServiceTests {

    private static final Instant NOW = Instant.parse("2026-09-16T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    private InMemoryExplorationStore store;
    private RecordingRunDispatcher dispatcher;
    private ExplorationService service;

    @BeforeEach
    void setUp() {
        store = new InMemoryExplorationStore();
        dispatcher = new RecordingRunDispatcher();
        service = new ExplorationService(store, dispatcher, CLOCK);
    }

    @Test
    void missingRegionCompletesWithClarificationWithoutDispatchingAi() {
        var result = service.create(
                ExplorationActor.guest("guest-a"),
                new CreateExplorationCommand("조용한 한옥 여행을 하고 싶어요", "ko-KR", null));

        assertThat(result.run().status()).isEqualTo(ExplorationRunStatus.COMPLETED);
        assertThat(result.run().outcome()).isEqualTo(ExplorationRunOutcome.CLARIFICATION_REQUIRED);
        assertThat(result.run().clarification()).isEqualTo(new ExplorationClarification(
                "REGION_MISSING",
                "어느 지역을 둘러보고 싶으신가요?",
                true));
        assertThat(dispatcher.dispatchCount).isZero();
        assertThat(store.explorationCount()).isEqualTo(1);
        assertThat(store.turnCount(result.explorationId())).isEqualTo(1);
    }

    @Test
    void canonicalRegionCreatesQueuedRunAndDispatchesOnce() {
        var result = service.create(
                ExplorationActor.member(UUID.randomUUID()),
                new CreateExplorationCommand("한옥과 역사 이야기를 보고 싶어요", "ko-KR", "kr-11-seoul"));

        assertThat(result.run().status()).isEqualTo(ExplorationRunStatus.QUEUED);
        assertThat(result.run().outcome()).isNull();
        assertThat(result.regionCode()).isEqualTo("kr-11-seoul");
        assertThat(dispatcher.dispatchCount).isEqualTo(1);
        assertThat(dispatcher.lastRequest.explorationId()).isEqualTo(result.explorationId());
    }

    @Test
    void invalidCreateDoesNotPersistExplorationOrRawTurn() {
        assertThatThrownBy(() -> service.create(
                ExplorationActor.guest("guest-a"),
                new CreateExplorationCommand(" ", "ko-KR", null)))
                .isInstanceOf(ExplorationInputInvalidException.class)
                .hasMessageContaining("query");

        assertThat(store.explorationCount()).isZero();
        assertThat(store.totalTurnCount()).isZero();
        assertThat(dispatcher.dispatchCount).isZero();
    }

    @Test
    void anotherActorCannotReadOrAppendTurn() {
        var owner = ExplorationActor.guest("guest-owner");
        var other = ExplorationActor.guest("guest-other");
        var created = service.create(owner, new CreateExplorationCommand("한옥 질문", "ko-KR", null));

        assertThatThrownBy(() -> service.get(other, created.explorationId()))
                .isInstanceOf(ExplorationNotFoundException.class);
        assertThatThrownBy(() -> service.createTurn(
                other,
                created.explorationId(),
                new CreateExplorationTurnCommand(UUID.randomUUID(), 0, "전주로 갈게요", "kr-45-jeonju")))
                .isInstanceOf(ExplorationNotFoundException.class);

        assertThat(store.turnCount(created.explorationId())).isEqualTo(1);
    }

    @Test
    void turnPersistsInputAndDuplicateClientTurnReturnsOriginalRun() {
        var owner = ExplorationActor.guest("guest-owner");
        var created = service.create(owner, new CreateExplorationCommand("첫 한옥 질문", "ko-KR", null));
        var clientTurnId = UUID.randomUUID();
        var command = new CreateExplorationTurnCommand(clientTurnId, 0, "전주로 갈게요", "kr-45-jeonju");

        var first = service.createTurn(owner, created.explorationId(), command);
        var replay = service.createTurn(owner, created.explorationId(), command);

        assertThat(first.run().status()).isEqualTo(ExplorationRunStatus.QUEUED);
        assertThat(replay.run().id()).isEqualTo(first.run().id());
        assertThat(store.turnCount(created.explorationId())).isEqualTo(2);
        assertThat(store.storedQuery(created.explorationId(), clientTurnId)).contains("전주로 갈게요");
        assertThat(dispatcher.dispatchCount).isEqualTo(1);
    }

    @Test
    void invalidTurnDoesNotPersistRawInput() {
        var owner = ExplorationActor.guest("guest-owner");
        var created = service.create(owner, new CreateExplorationCommand("첫 한옥 질문", "ko-KR", null));

        assertThatThrownBy(() -> service.createTurn(
                owner,
                created.explorationId(),
                new CreateExplorationTurnCommand(UUID.randomUUID(), 0, " ", "kr-45-jeonju")))
                .isInstanceOf(ExplorationInputInvalidException.class)
                .hasMessageContaining("query");

        assertThat(store.turnCount(created.explorationId())).isEqualTo(1);
        assertThat(dispatcher.dispatchCount).isZero();
    }

    @Test
    void privacySafetyAndScopeRejectionsDoNotPersistOrDispatch() {
        var owner = ExplorationActor.guest("guest-owner");

        assertThatThrownBy(() -> service.create(owner, new CreateExplorationCommand(
                "010-1234-5678로 연락 가능한 한옥 숙소", "ko-KR", "kr-45-jeonju")))
                .isInstanceOf(ExplorationInputRejectedException.class)
                .hasMessageContaining("PRIVACY_REDACT_REQUIRED");
        assertThatThrownBy(() -> service.create(owner, new CreateExplorationCommand(
                "<script>alert(1)</script> 전주 한옥", "ko-KR", "kr-45-jeonju")))
                .isInstanceOf(ExplorationInputRejectedException.class)
                .hasMessageContaining("SAFETY_BLOCKED");
        assertThatThrownBy(() -> service.create(owner, new CreateExplorationCommand(
                "조선 시대 역사 시험 답안", "ko-KR", "kr-45-jeonju")))
                .isInstanceOf(ExplorationInputRejectedException.class)
                .hasMessageContaining("JOURNEY_SCOPE_UNSUPPORTED");

        assertThat(store.explorationCount()).isZero();
        assertThat(store.totalTurnCount()).isZero();
        assertThat(dispatcher.dispatchCount).isZero();
    }

    private static final class RecordingRunDispatcher implements ExplorationRunDispatcher {
        private int dispatchCount;
        private ExplorationRunRequest lastRequest;

        @Override
        public void dispatch(ExplorationRunRequest request) {
            dispatchCount++;
            lastRequest = request;
        }
    }
}
