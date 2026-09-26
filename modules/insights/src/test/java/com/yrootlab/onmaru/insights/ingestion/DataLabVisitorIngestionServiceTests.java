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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DataLabVisitorIngestionServiceTests {

    @Test
    void persistsTheFetchedDailyObservationBatch() {
        var fetched = List.of(complete("kr-45-jeonju", 18_240L));
        var writer = new FakeRevisionWriter(List.of());
        var observer = new RecordingObserver();
        var service = new DataLabVisitorIngestionService(
                () -> new DataLabVisitorFetchResult(fetched, List.of(), false), writer, observer);

        var result = service.sync();

        assertThat(result.published()).isTrue();
        assertThat(result.observationCount()).isEqualTo(1);
        assertThat(writer.activeObservations).containsExactlyElementsOf(fetched);
        assertThat(writer.replaceCalls).isEqualTo(1);
        assertThat(observer.events).containsExactly(
                new ObservationEvent(DataLabCollectionOutcome.PUBLISHED, null));
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

    @Test
    void keepsPreviousRevisionAndReportsSkippedReasons() {
        var previouslyActive = List.of(complete("kr-45-jeonju", 17_000L));
        var writer = new FakeRevisionWriter(previouslyActive);
        var observer = new RecordingObserver();
        var exclusions = List.of(
                new DataLabCollectionExclusion("kr-48", DataLabCollectionReason.PENDING_MAPPING),
                new DataLabCollectionExclusion(null, DataLabCollectionReason.NO_ACTIVE_MAPPING));
        var service = new DataLabVisitorIngestionService(
                () -> new DataLabVisitorFetchResult(List.of(), exclusions, false), writer, observer);

        var result = service.sync();

        assertThat(result.published()).isFalse();
        assertThat(result.skippedCount()).isEqualTo(2);
        assertThat(result.quarantinedCount()).isZero();
        assertThat(writer.activeObservations).containsExactlyElementsOf(previouslyActive);
        assertThat(writer.replaceCalls).isZero();
        assertThat(observer.events).containsExactly(
                new ObservationEvent(DataLabCollectionOutcome.SKIPPED, DataLabCollectionReason.PENDING_MAPPING),
                new ObservationEvent(DataLabCollectionOutcome.SKIPPED, DataLabCollectionReason.NO_ACTIVE_MAPPING));
    }

    @Test
    void keepsPreviousRevisionAndReportsQuarantineReasons() {
        var previouslyActive = List.of(complete("kr-45-jeonju", 17_000L));
        var writer = new FakeRevisionWriter(previouslyActive);
        var observer = new RecordingObserver();
        var exclusions = List.of(new DataLabCollectionExclusion(
                "kr-45-jeonju", DataLabCollectionReason.RESPONSE_SCOPE_MISMATCH));
        var service = new DataLabVisitorIngestionService(
                () -> new DataLabVisitorFetchResult(List.of(), exclusions, true), writer, observer);

        var result = service.sync();

        assertThat(result.published()).isFalse();
        assertThat(result.skippedCount()).isZero();
        assertThat(result.quarantinedCount()).isEqualTo(1);
        assertThat(writer.activeObservations).containsExactlyElementsOf(previouslyActive);
        assertThat(writer.replaceCalls).isZero();
        assertThat(observer.events).containsExactly(new ObservationEvent(
                DataLabCollectionOutcome.QUARANTINED,
                DataLabCollectionReason.RESPONSE_SCOPE_MISMATCH));
    }

    @Test
    void oneGuardSerializesSchedulerAndOperationsServiceInstances() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var guard = new InMemoryDataLabCollectionGuard();
        DataLabVisitorSource blockingSource = () -> {
            entered.countDown();
            try {
                if (!release.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("timed out waiting for test release");
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(exception);
            }
            return new DataLabVisitorFetchResult(List.of(), List.of(), false);
        };
        var schedulerService = new DataLabVisitorIngestionService(
                blockingSource, observations -> { }, DataLabCollectionObserver.NOOP, guard);
        var operationsService = new DataLabVisitorIngestionService(
                blockingSource, observations -> { }, DataLabCollectionObserver.NOOP, guard);

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var running = executor.submit(schedulerService::sync);
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();

            assertThatThrownBy(operationsService::sync)
                    .isInstanceOf(DataLabCollectionAlreadyRunningException.class);

            release.countDown();
            running.get(5, TimeUnit.SECONDS);
        }
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

    private static final class RecordingObserver implements DataLabCollectionObserver {
        private final List<ObservationEvent> events = new ArrayList<>();

        @Override
        public void record(DataLabCollectionOutcome outcome, DataLabCollectionReason reason) {
            events.add(new ObservationEvent(outcome, reason));
        }
    }

    private record ObservationEvent(DataLabCollectionOutcome outcome, DataLabCollectionReason reason) {
    }
}
