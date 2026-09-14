package com.yrootlab.onmaru.insights.observation;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class InMemoryObservationStoreTests {

    private final InMemoryObservationStore store = new InMemoryObservationStore();

    @Test
    void savesTypedVisitorObservationWithProvenanceAndNullableMissingValue() {
        store.save(new VisitorObservation(
                "kto-datalab",
                "kr-45-jeonju",
                LocalDate.parse("2026-09-14"),
                ObservationMetric.VISITOR_COUNT,
                null,
                "persons",
                SpatialLevel.SIGUNGU,
                ObservationCoverageStatus.NOT_AVAILABLE,
                Instant.parse("2026-09-15T02:30:00Z")));

        assertThat(store.visitorObservations()).singleElement().satisfies(observation -> {
            assertThat(observation.value()).isNull();
            assertThat(observation.coverageStatus()).isEqualTo(ObservationCoverageStatus.NOT_AVAILABLE);
            assertThat(observation.basisDate()).isEqualTo(LocalDate.parse("2026-09-14"));
            assertThat(observation.spatialLevel()).isEqualTo(SpatialLevel.SIGUNGU);
            assertThat(observation.provider()).isEqualTo("kto-datalab");
        });
    }

    @Test
    void savesConcentrationObservationWithUnknownTargetInsteadOfInventingPlaceLink() {
        store.save(new ConcentrationObservation(
                "kto-datalab",
                "UNKNOWN",
                "kr-45-jeonju",
                LocalDate.parse("2026-09-14"),
                ObservationMetric.CONGESTION_SCORE,
                72.4,
                "score",
                ObservationCoverageStatus.PARTIAL));

        assertThat(store.concentrationObservations()).singleElement().satisfies(observation -> {
            assertThat(observation.targetKey()).isEqualTo("UNKNOWN");
            assertThat(observation.value()).isEqualTo(72.4);
            assertThat(observation.coverageStatus()).isEqualTo(ObservationCoverageStatus.PARTIAL);
        });
    }
}
