package com.yrootlab.onmaru.insights.query;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class InsightsQueryServiceTests {

    private static final Instant NOW = Instant.parse("2026-09-15T08:35:00Z");
    private final InMemoryInsightsQueryStore store = new InMemoryInsightsQueryStore();
    private final InsightsQueryService service = new InsightsQueryService(store, Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void listsRegionalVisitorObservationsWithoutSynthesizingMissingValuesAsZero() {
        store.save(new Observation(
                "obs-1",
                region(),
                LocalDate.parse("2026-09-14"),
                "VISITOR_COUNT",
                null,
                "persons",
                "SIGUNGU",
                "MISSING"
        ));

        ObservationPage page = service.listObservations(
                "kr-45-jeonju",
                "VISITOR_COUNT",
                LocalDate.parse("2026-09-14"),
                LocalDate.parse("2026-09-14"));

        assertThat(page.coverageStatus()).isEqualTo("MISSING");
        assertThat(page.generatedAt()).isEqualTo(NOW);
        assertThat(page.items()).singleElement().satisfies(item -> {
            assertThat(item.value()).isNull();
            assertThat(item.unit()).isEqualTo("persons");
            assertThat(item.spatialLevel()).isEqualTo("SIGUNGU");
            assertThat(item.coverageStatus()).isEqualTo("MISSING");
        });
    }

    @Test
    void returnsStableMissingHeatmapWhenObservationCoverageIsAbsent() {
        HeatmapResponse response = service.heatmap("kr-45-jeonju", LocalDate.parse("2026-09-14"), "CONGESTION_SCORE");

        assertThat(response.coverageStatus()).isEqualTo("MISSING");
        assertThat(response.observedDate()).isEqualTo(LocalDate.parse("2026-09-14"));
        assertThat(response.spots()).isEmpty();
    }

    @Test
    void returnsHeatmapSpotsWithCoverageStatus() {
        store.save(new HeatSpot(
                "heat-p-jeonju-hanok-village-2026-09-14",
                "p-jeonju-hanok-village",
                "전주 한옥마을",
                region(),
                new Coordinates(35.8151, 127.1530),
                18240L,
                72.4,
                "BUSY",
                1.8,
                "COMPLETE",
                LocalDate.parse("2026-09-14"),
                "CONGESTION_SCORE"
        ));

        HeatmapResponse response = service.heatmap("kr-45-jeonju", LocalDate.parse("2026-09-14"), "CONGESTION_SCORE");

        assertThat(response.coverageStatus()).isEqualTo("COMPLETE");
        assertThat(response.spots()).singleElement().satisfies(spot -> {
            assertThat(spot.visitorCount()).isEqualTo(18240L);
            assertThat(spot.congestionScore()).isEqualTo(72.4);
            assertThat(spot.coverageStatus()).isEqualTo("COMPLETE");
        });
    }

    private RegionRef region() {
        return new RegionRef("kr-45-jeonju", "전북 전주시", "CITY", "kr-45");
    }
}
