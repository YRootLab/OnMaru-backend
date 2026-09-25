package com.yrootlab.onmaru.insights.ingestion;

import com.yrootlab.onmaru.insights.observation.ObservationCoverageStatus;
import com.yrootlab.onmaru.insights.observation.ObservationMetric;
import com.yrootlab.onmaru.insights.observation.SpatialLevel;
import com.yrootlab.onmaru.insights.observation.VisitorObservation;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DataLabVisitorIngestionServiceTests {

    @Test
    void persistsTheFetchedDailyObservationBatch() {
        var fetched = List.of(complete("kr-45-jeonju", 18_240L));
        var writer = new FakeRevisionWriter(List.of());
        var service = new DataLabVisitorIngestionService(() -> fetched, writer);

        service.sync();

        assertThat(writer.activeObservations).containsExactlyElementsOf(fetched);
        assertThat(writer.replaceCalls).isEqualTo(1);
    }

    @Test
    void propagatesSourceFailureWithoutReplacingThePreviouslyActiveBatch() {
        var previouslyActive = List.of(complete("kr-45-jeonju", 17_000L));
        var writer = new FakeRevisionWriter(previouslyActive);
        var service = new DataLabVisitorIngestionService(
                () -> { throw new IllegalStateException("DataLab unavailable"); }, writer);

        assertThatThrownBy(service::sync)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("DataLab unavailable");

        assertThat(writer.activeObservations).containsExactlyElementsOf(previouslyActive);
        assertThat(writer.replaceCalls).isZero();
    }

    private VisitorObservation complete(String regionCode, long count) {
        return new VisitorObservation(
                "KTO_DATALAB", regionCode, LocalDate.parse("2026-09-25"),
                ObservationMetric.VISITOR_COUNT, count, "persons", SpatialLevel.SIGUNGU,
                ObservationCoverageStatus.COMPLETE, Instant.parse("2026-09-26T00:00:00Z"));
    }

    private static final class FakeRevisionWriter implements DataLabVisitorRevisionWriter {

        private List<VisitorObservation> activeObservations;
        private int replaceCalls;

        private FakeRevisionWriter(List<VisitorObservation> activeObservations) {
            this.activeObservations = new ArrayList<>(activeObservations);
        }

        @Override
        public void replaceActive(List<VisitorObservation> observations) {
            activeObservations = new ArrayList<>(observations);
            replaceCalls++;
        }
    }
}
