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
    void reportsStaleObservationCoverageWithoutMaskingFreshness() {
        store.save(new Observation(
                "obs-stale",
                region(),
                LocalDate.parse("2026-09-07"),
                "VISITOR_COUNT",
                17320L,
                "persons",
                "SIGUNGU",
                "STALE"
        ));

        ObservationPage page = service.listObservations(
                "kr-45-jeonju",
                "VISITOR_COUNT",
                LocalDate.parse("2026-09-07"),
                LocalDate.parse("2026-09-07"));

        assertThat(page.coverageStatus()).isEqualTo("STALE");
        assertThat(page.items()).singleElement().satisfies(item -> {
            assertThat(item.value()).isEqualTo(17320L);
            assertThat(item.coverageStatus()).isEqualTo("STALE");
        });
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

    @Test
    void acceptsFrontendVisitCountMetricAndReturnsDerivedCongestionSpots() {
        store.save(new HeatSpot(
                "heat-visit-count", "region:kr-45-jeonju", "전주시", region(),
                new Coordinates(35.8151, 127.1530), 18240L, 72.4, "BUSY", 1.8,
                "COMPLETE", LocalDate.parse("2026-09-14"), "CONGESTION_SCORE"));

        HeatmapResponse response = service.heatmap(
                "kr-45-jeonju", LocalDate.parse("2026-09-14"), "VISIT_COUNT");

        assertThat(response.metric()).isEqualTo("VISIT_COUNT");
        assertThat(response.coverageStatus()).isEqualTo("COMPLETE");
        assertThat(response.spots()).singleElement().extracting(HeatSpot::id)
                .isEqualTo("heat-visit-count");
    }

    @Test
    void usesTheLatestPublishedHeatmapDateWhenDateIsOmitted() {
        store.save(new HeatSpot(
                "heat-old", "region:kr-45-jeonju", "전주시", region(),
                new Coordinates(35.8151, 127.1530), 10L, 10.0, "RELAXED", 1.0,
                "COMPLETE", LocalDate.parse("2026-08-21"), "CONGESTION_SCORE"));
        store.save(new HeatSpot(
                "heat-latest", "region:kr-45-jeonju", "전주시", region(),
                new Coordinates(35.8151, 127.1530), 20L, 20.0, "RELAXED", 1.0,
                "COMPLETE", LocalDate.parse("2026-08-22"), "CONGESTION_SCORE"));

        HeatmapResponse response = service.heatmap(null, null, "CONGESTION_SCORE");

        assertThat(response.observedDate()).isEqualTo(LocalDate.parse("2026-08-22"));
        assertThat(response.spots()).extracting(HeatSpot::id).containsExactly("heat-latest");
    }

    @Test
    void returnsFrontendCompatibleViewportHeatmapWithActualDailySeries() {
        store.save(new HeatSpot(
                "heat-old", "region:kr-45-jeonju", "전주시", region(),
                new Coordinates(35.8151, 127.1530), 10L, 25.0, "RELAXED", 1.0,
                "COMPLETE", LocalDate.parse("2026-09-14"), "CONGESTION_SCORE"));
        store.save(new HeatSpot(
                "heat-latest", "region:kr-45-jeonju", "전주시", region(),
                new Coordinates(35.8151, 127.1530), 20L, 75.0, "SURGE", 1.8,
                "COMPLETE", LocalDate.parse("2026-09-15"), "CONGESTION_SCORE"));
        store.save(new HeatSpot(
                "heat-outside", "region:kr-11-seoul", "서울시",
                new RegionRef("kr-11-seoul", "서울시", "CITY", "kr-11"),
                new Coordinates(37.5665, 126.9780), 30L, 90.0, "SURGE", 2.0,
                "COMPLETE", LocalDate.parse("2026-09-15"), "CONGESTION_SCORE"));

        MapHeatResponse response = service.mapHeat(35.8151, 127.1530, 15_000);

        assertThat(response.updatedAt()).isEqualTo(NOW);
        assertThat(response.count()).isEqualTo(1);
        assertThat(response.days()).extracting(MapHeatDay::ymd, MapHeatDay::weekday)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("20260914", "월요일"),
                        org.assertj.core.groups.Tuple.tuple("20260915", "화요일"));
        assertThat(response.spots()).singleElement().satisfies(spot -> {
            assertThat(spot.id()).isEqualTo("heat-latest");
            assertThat(spot.placeId()).isEqualTo("region:kr-45-jeonju");
            assertThat(spot.name()).isEqualTo("전주시");
            assertThat(spot.lat()).isEqualTo(35.8151);
            assertThat(spot.lng()).isEqualTo(127.1530);
            assertThat(spot.district()).isEqualTo("전북 전주시");
            assertThat(spot.visitorCount()).isEqualTo(20L);
            assertThat(spot.congestionScore()).isEqualTo(75.0);
            assertThat(spot.congestionLevel()).isEqualTo("surge");
            assertThat(spot.surgeMultiplier()).isEqualTo(1.8);
            assertThat(spot.intensity()).isEqualTo(0.75);
            assertThat(spot.series()).containsExactly(25.0, 75.0);
            assertThat(spot.updatedAt()).isEqualTo(NOW);
        });
    }

    private RegionRef region() {
        return new RegionRef("kr-45-jeonju", "전북 전주시", "CITY", "kr-45");
    }
}
