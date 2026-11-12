package com.yrootlab.onmaru.journey.exploration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import com.yrootlab.onmaru.journey.events.JourneyRunEventSink;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExplorationRegionValidationTests {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-16T00:00:00Z"), ZoneOffset.UTC);
    private static final ExplorationActor OWNER = ExplorationActor.member(UUID.randomUUID());

    private final InMemoryExplorationStore store = new InMemoryExplorationStore();
    private final NoopDispatcher dispatcher = new NoopDispatcher();
    private ExplorationService service;

    @BeforeEach
    void setUp() {
        service = new ExplorationService(store, dispatcher, CLOCK,
                (actor, state) -> true, JourneyRunEventSink.noop(), null,
                regionCode -> Set.of("kr-11", "kr-11-jongno", "kr-45").contains(regionCode));
    }

    @Test
    void knownCanonicalRegionIsAccepted() {
        var created = service.create(OWNER, new CreateExplorationCommand("한옥 코스", "ko-KR", "kr-11-jongno"));

        assertThat(created.regionCode()).isEqualTo("kr-11-jongno");
    }

    @Test
    void wellFormedButUnknownRegionIsRejectedBeforePersistence() {
        assertThatThrownBy(() -> service.create(OWNER, new CreateExplorationCommand("한옥 코스", "ko-KR", "kr-99")))
                .isInstanceOf(ExplorationInputInvalidException.class)
                .hasMessageContaining("regionCode");

        assertThat(store.explorationCount()).isZero();
        assertThat(dispatcher.count).isZero();
    }

    @Test
    void nonCanonicalRegionFormatIsRejectedEvenIfTheCatalogWouldMatch() {
        assertThatThrownBy(() -> service.create(OWNER, new CreateExplorationCommand("한옥 코스", "ko-KR", "region-seoul")))
                .isInstanceOf(ExplorationInputInvalidException.class);
        assertThatThrownBy(() -> service.create(OWNER, new CreateExplorationCommand("한옥 코스", "ko-KR", "11")))
                .isInstanceOf(ExplorationInputInvalidException.class);
    }

    @Test
    void absentRegionIsNationwideAndNotCheckedAgainstTheCatalog() {
        var created = service.create(OWNER, new CreateExplorationCommand("한옥 코스", "ko-KR", null));

        assertThat(created.regionCode()).isNull();
        assertThat(dispatcher.count).isEqualTo(1);
    }

    @Test
    void unknownRegionOnTurnIsRejected() {
        var created = service.create(OWNER, new CreateExplorationCommand("한옥 코스", "ko-KR", null));
        service.completeRun(created.explorationId(), created.run().id(), ExplorationRunOutcome.INITIAL_BOARD);
        var baseVersion = service.get(OWNER, created.explorationId()).stateVersion();

        assertThatThrownBy(() -> service.createTurn(OWNER, created.explorationId(),
                new CreateExplorationTurnCommand(UUID.randomUUID(), baseVersion, "전주로", "kr-99", null)))
                .isInstanceOf(ExplorationInputInvalidException.class);
    }

    private static final class NoopDispatcher implements ExplorationRunDispatcher {
        int count;

        @Override
        public void dispatch(ExplorationRunRequest request) {
            count++;
        }
    }
}
