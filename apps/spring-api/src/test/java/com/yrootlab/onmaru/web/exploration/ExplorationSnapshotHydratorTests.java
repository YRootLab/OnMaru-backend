package com.yrootlab.onmaru.web.exploration;

import com.yrootlab.onmaru.catalog.application.query.detail.CoordinatesProjection;
import com.yrootlab.onmaru.catalog.application.query.detail.ImageProjection;
import com.yrootlab.onmaru.catalog.application.query.detail.InMemoryPlaceDetailStore;
import com.yrootlab.onmaru.catalog.application.query.detail.PlaceDetailQueryService;
import com.yrootlab.onmaru.catalog.application.query.detail.PlaceProjection;
import com.yrootlab.onmaru.catalog.application.query.detail.RegionProjection;
import com.yrootlab.onmaru.journey.exploration.ExplorationRun;
import com.yrootlab.onmaru.journey.exploration.ExplorationRunOutcome;
import com.yrootlab.onmaru.journey.exploration.ExplorationRunStatus;
import com.yrootlab.onmaru.journey.exploration.ExplorationSnapshot;
import com.yrootlab.onmaru.journey.exploration.PersistedExplorationBoard;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ExplorationSnapshotHydratorTests {

    private static final Instant NOW = Instant.parse("2026-11-17T00:00:00Z");

    private InMemoryPlaceDetailStore placeDetailStore;
    private ExplorationSnapshotHydrator hydrator;

    @BeforeEach
    void setUp() {
        placeDetailStore = new InMemoryPlaceDetailStore();
        hydrator = new ExplorationSnapshotHydrator(new PlaceDetailQueryService(placeDetailStore, (memberId, placeId) -> false));
    }

    @Test
    @SuppressWarnings("unchecked")
    void hydratesMultiPlaceLlmProposalBoardWithReasonsSummaryAndUnavailableRefs() {
        placeDetailStore.add(publicPlace("p-jeonju-1", "전주 한옥마을", "HANOK_VILLAGE"));
        placeDetailStore.add(publicPlace("p-jeonju-2", "경기전", "CULTURAL_HERITAGE"));
        placeDetailStore.add(PlaceProjection.hidden("p-hidden-3"));

        var board = new PersistedExplorationBoard(
                "LLM",
                null,
                "PROPOSAL",
                List.of("p-jeonju-1", "p-jeonju-2", "p-hidden-3", "p-jeonju-1"),
                Map.of(
                        "p-jeonju-1", "전통 한옥이 잘 보존된 대표 산책 코스입니다.",
                        "p-jeonju-2", "태조 어진을 모신 역사 공간입니다."),
                "전주 한옥과 역사를 함께 둘러보는 당일치기 일정입니다.");
        var snapshot = completedSnapshot(ExplorationRunOutcome.PROPOSAL, board);

        var hydrated = hydrator.hydrate(snapshot);

        assertThat(hydrated.execution())
                .containsEntry("dataMode", "PUBLIC")
                .containsEntry("engine", "LLM")
                .containsEntry("rankingVersion", "ai-rag-v1")
                .containsEntry("datasetRevision", "catalog-current");
        assertThat(hydrated.unavailableRefs())
                .containsExactly(Map.of("type", "PLACE", "id", "p-hidden-3"));

        Map<String, Object> responseBoard = (Map<String, Object>) hydrated.board();
        assertThat(responseBoard).containsEntry("querySummary", "전주 한옥과 역사를 함께 둘러보는 당일치기 일정입니다.");
        List<Map<String, Object>> candidates = (List<Map<String, Object>>) responseBoard.get("candidates");
        assertThat(candidates).hasSize(2);
        assertThat(candidates.get(0)).containsEntry("placeRef", Map.of("type", "PLACE", "id", "p-jeonju-1"))
                .containsEntry("reason", "전통 한옥이 잘 보존된 대표 산책 코스입니다.");
        assertThat(candidates.get(1)).containsEntry("placeRef", Map.of("type", "PLACE", "id", "p-jeonju-2"))
                .containsEntry("reason", "태조 어진을 모신 역사 공간입니다.");
    }

    @Test
    void handlesNoResultsAndRunningStatusWithoutExposingBoardOrStaleDegradedReason() {
        var noResultBoard = new PersistedExplorationBoard("LLM", null, "NO_RESULTS", List.of(), Map.of(), null);
        var noResultsHydrated = hydrator.hydrate(completedSnapshot(ExplorationRunOutcome.NO_RESULTS, noResultBoard));

        assertThat(noResultsHydrated.board()).isNull();
        assertThat(noResultsHydrated.unavailableRefs()).isEmpty();
        assertThat(noResultsHydrated.execution()).containsEntry("engine", "LLM")
                .containsEntry("rankingVersion", "ai-rag-v1");

        var runningSnapshot = new ExplorationSnapshot(
                UUID.randomUUID(),
                1,
                "kr-45-jeonju",
                new ExplorationRun(
                        UUID.randomUUID(),
                        ExplorationRunStatus.RUNNING,
                        "LLM",
                        "RETRIEVING",
                        null,
                        null,
                        NOW,
                        NOW,
                        NOW.plusSeconds(30)),
                NOW,
                new PersistedExplorationBoard("BASELINE", "AI_TIMEOUT", "INITIAL_BOARD", List.of("p-old"), Map.of(), null));

        var response = ExplorationResponse.from(runningSnapshot, hydrator.hydrate(runningSnapshot));
        assertThat(response.board()).isNull();
        assertThat(response.latestRun().engine()).isEqualTo("LLM");
        assertThat(response.latestRun().degradedReason()).isNull();
    }

    private ExplorationSnapshot completedSnapshot(ExplorationRunOutcome outcome, PersistedExplorationBoard board) {
        return new ExplorationSnapshot(
                UUID.randomUUID(),
                1,
                "kr-45-jeonju",
                new ExplorationRun(
                        UUID.randomUUID(),
                        ExplorationRunStatus.COMPLETED,
                        board.engine(),
                        null,
                        outcome,
                        null,
                        NOW,
                        NOW,
                        NOW.plusSeconds(30)),
                NOW,
                board);
    }

    private static PlaceProjection publicPlace(String id, String name, String category) {
        return PlaceProjection.publicPlace(
                id,
                name,
                category,
                new RegionProjection("kr-45-jeonju", "전북 전주"),
                "전북특별자치도 전주시 완산구",
                new CoordinatesProjection(35.815, 127.153),
                List.of(new ImageProjection("https://cdn.onmaru.site/" + id + ".jpg", name)),
                name + " 설명",
                List.of(),
                null);
    }
}
