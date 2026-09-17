package com.yrootlab.onmaru.journey.savedjourney;

import com.yrootlab.onmaru.journey.actions.ResourceRef;
import com.yrootlab.onmaru.journey.exploration.CreateExplorationCommand;
import com.yrootlab.onmaru.journey.exploration.ExplorationActiveRunException;
import com.yrootlab.onmaru.journey.exploration.ExplorationActor;
import com.yrootlab.onmaru.journey.exploration.ExplorationRunOutcome;
import com.yrootlab.onmaru.journey.exploration.ExplorationService;
import com.yrootlab.onmaru.journey.exploration.InMemoryExplorationStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SavedJourneyServiceTests {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-17T00:00:00Z"), ZoneOffset.UTC);

    private InMemorySavedJourneyStore store;
    private ExplorationService explorations;
    private SavedJourneyService service;
    private UUID memberId;
    private ExplorationActor actor;

    @BeforeEach
    void setUp() {
        store = new InMemorySavedJourneyStore();
        explorations = new ExplorationService(new InMemoryExplorationStore(), request -> {}, CLOCK);
        service = new SavedJourneyService(store, explorations, CLOCK, ref -> !ref.id().equals("hidden"));
        memberId = UUID.randomUUID();
        actor = ExplorationActor.member(memberId);
    }

    @Test
    void saveReturnsExistingSnapshotForSameSourceVersion() {
        var exploration = completedExploration();

        var first = service.save(new CreateSavedJourneyCommand(
                memberId, actor, exploration.explorationId(), 0, "전주 한옥 산책"));
        var replay = service.save(new CreateSavedJourneyCommand(
                memberId, actor, exploration.explorationId(), 0, "제목을 바꿔도 기존 저장 반환"));

        assertThat(replay.created()).isFalse();
        assertThat(replay.journey().savedJourneyId()).isEqualTo(first.journey().savedJourneyId());
        assertThat(replay.journey().title()).isEqualTo("전주 한옥 산책");
        assertThat(store.countFor(memberId)).isEqualTo(1);
    }

    @Test
    void activeRunCannotBeSaved() {
        var active = explorations.create(actor, new CreateExplorationCommand("전주 한옥", "ko-KR", "kr-45-jeonju"));

        assertThatThrownBy(() -> service.save(new CreateSavedJourneyCommand(
                memberId, actor, active.explorationId(), 0, "아직 실행 중인 여정")))
                .isInstanceOf(ExplorationActiveRunException.class);

        assertThat(store.countFor(memberId)).isZero();
    }

    @Test
    void anotherMemberCannotReadOrDeleteSavedJourney() {
        var saved = service.save(new CreateSavedJourneyCommand(
                memberId, actor, completedExploration().explorationId(), 0, "내 저장 여정")).journey();
        var other = UUID.randomUUID();

        assertThatThrownBy(() -> service.get(other, saved.savedJourneyId()))
                .isInstanceOf(SavedJourneyNotFoundException.class);
        assertThatThrownBy(() -> service.delete(other, saved.savedJourneyId()))
                .isInstanceOf(SavedJourneyNotFoundException.class);

        assertThat(service.get(memberId, saved.savedJourneyId()).savedJourneyId())
                .isEqualTo(saved.savedJourneyId());
    }

    @Test
    void resumeFiltersUnavailableRefsAndCreatesEmptyVersionZeroWhenAllAreUnavailable() {
        var saved = store.save(memberId, SavedJourneySnapshot.seed(
                UUID.randomUUID(),
                3,
                "숨겨진 장소 여정",
                "kr-45-jeonju",
                List.of(place("hidden")),
                List.of(place("hidden")),
                List.of(),
                CLOCK.instant()), CLOCK.instant(), 100).journey();

        var resumed = service.resume(memberId, saved.savedJourneyId());

        assertThat(resumed.unavailableRefs()).containsExactly(place("hidden"));
        assertThat(resumed.exploration().stateVersion()).isZero();
        assertThat(resumed.exploration().orderedRefs()).isEmpty();
        assertThat(resumed.exploration().pinnedRefs()).isEmpty();
    }

    @Test
    void listIsOwnerScopedAndOrderedBySavedAtDescending() {
        var first = service.save(new CreateSavedJourneyCommand(
                memberId, actor, completedExploration().explorationId(), 0, "첫 저장")).journey();
        var otherMember = UUID.randomUUID();
        store.save(otherMember, first.snapshot(), CLOCK.instant().plusSeconds(1), 100);

        var page = service.list(memberId, 20, null);

        assertThat(page.items()).extracting(SavedJourneySummary::savedJourneyId)
                .containsExactly(first.savedJourneyId());
        assertThat(page.hasMore()).isFalse();
    }

    @Test
    void listUsesCursorToContinueAfterTheLastItem() {
        var first = service.save(new CreateSavedJourneyCommand(
                memberId, actor, completedExploration().explorationId(), 0, "첫 저장")).journey();
        var secondSnapshot = SavedJourneySnapshot.seed(
                UUID.randomUUID(), 0, "둘째 저장", "kr-45-jeonju",
                List.of(place("p-jeonju-hanok-village")), List.of(), List.of(), CLOCK.instant());
        var second = store.save(memberId, secondSnapshot, CLOCK.instant().plusSeconds(1), 100).journey();

        var firstPage = service.list(memberId, 1, null);
        var secondPage = service.list(memberId, 1, firstPage.nextCursor());

        assertThat(firstPage.items()).extracting(SavedJourneySummary::savedJourneyId)
                .containsExactly(second.savedJourneyId());
        assertThat(firstPage.hasMore()).isTrue();
        assertThat(firstPage.nextCursor()).isNotBlank();
        assertThat(secondPage.items()).extracting(SavedJourneySummary::savedJourneyId)
                .containsExactly(first.savedJourneyId());
        assertThat(secondPage.hasMore()).isFalse();
    }

    private com.yrootlab.onmaru.journey.exploration.ExplorationSnapshot completedExploration() {
        var snapshot = explorations.create(actor, new CreateExplorationCommand("전주 한옥", "ko-KR", "kr-45-jeonju"));
        explorations.claimRun(snapshot.explorationId(), snapshot.run().id(), "INTERPRETING");
        explorations.completeRun(snapshot.explorationId(), snapshot.run().id(), ExplorationRunOutcome.INITIAL_BOARD);
        return explorations.get(actor, snapshot.explorationId());
    }

    private static ResourceRef place(String id) {
        return new ResourceRef("PLACE", id);
    }
}
