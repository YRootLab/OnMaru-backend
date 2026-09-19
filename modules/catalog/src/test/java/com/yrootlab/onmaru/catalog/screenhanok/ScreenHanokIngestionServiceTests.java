package com.yrootlab.onmaru.catalog.screenhanok;

import com.yrootlab.onmaru.catalog.application.query.hanok.HanokListCategory;
import com.yrootlab.onmaru.catalog.application.query.hanok.HanokListProjection;
import com.yrootlab.onmaru.catalog.application.query.hanok.InMemoryHanokListStore;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class ScreenHanokIngestionServiceTests {

    private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2026-09-19T03:00:00Z"), ZoneOffset.UTC);

    @Test
    void publishesOnlyMatchesThatCarryASource() {
        var hanoks = new InMemoryHanokListStore();
        hanoks.add(projection("p-001", "전주 한옥마을"));
        var researchPort = new StubResearchPort(List.of(
                new ScreenHanokMatch("p-001", ScreenHanokMediaType.K_DRAMA, "작품", "부제", List.of("#태그"),
                        "https://example.com/a", "출처 제목"),
                new ScreenHanokMatch("p-001", ScreenHanokMediaType.CINEMA, "무근거 작품", null, List.of(), null, null)));
        var store = new InMemoryScreenHanokPlacementStore();
        var service = new ScreenHanokIngestionService(hanoks, researchPort, store, FIXED_CLOCK);

        service.sync();

        assertThat(store.current()).hasSize(1);
        assertThat(store.current().getFirst().placeId()).isEqualTo("p-001");
        assertThat(store.current().getFirst().sourceUrl()).isEqualTo("https://example.com/a");
    }

    @Test
    void keepsLastPublishedSnapshotWhenResearchPortFails() {
        var hanoks = new InMemoryHanokListStore();
        hanoks.add(projection("p-001", "전주 한옥마을"));
        var store = new InMemoryScreenHanokPlacementStore();
        store.publish(List.of(new ScreenHanokPlacement(
                "p-existing", ScreenHanokMediaType.KPOP, "기존 작품", "", List.of(), "https://example.com/existing", "",
                Instant.now(FIXED_CLOCK))));
        var failingPort = new FailingResearchPort();
        var service = new ScreenHanokIngestionService(hanoks, failingPort, store, FIXED_CLOCK);

        service.sync();

        assertThat(store.current()).hasSize(1);
        assertThat(store.current().getFirst().placeId()).isEqualTo("p-existing");
    }

    @Test
    void doesNothingWhenThereAreNoPublishedCandidates() {
        var hanoks = new InMemoryHanokListStore();
        var store = new InMemoryScreenHanokPlacementStore();
        var researchPort = new AtomicReference<List<ScreenHanokCandidate>>();
        var service = new ScreenHanokIngestionService(hanoks, candidates -> {
            researchPort.set(candidates);
            return List.of();
        }, store, FIXED_CLOCK);

        service.sync();

        assertThat(researchPort.get()).isNull();
        assertThat(store.current()).isEmpty();
    }

    private static HanokListProjection projection(String placeId, String name) {
        return new HanokListProjection(
                placeId, name, HanokListCategory.HANOK, "11", "서울", "https://img", "요약", List.of(),
                Instant.now(FIXED_CLOCK), com.yrootlab.onmaru.catalog.application.query.hanok.HanokListStatus.PUBLIC);
    }

    private record StubResearchPort(List<ScreenHanokMatch> matches) implements ScreenHanokResearchPort {
        @Override
        public List<ScreenHanokMatch> research(List<ScreenHanokCandidate> candidates) {
            return matches;
        }
    }

    private static final class FailingResearchPort implements ScreenHanokResearchPort {
        @Override
        public List<ScreenHanokMatch> research(List<ScreenHanokCandidate> candidates) {
            throw new ScreenHanokResearchUnavailableException("boom", null);
        }
    }
}
