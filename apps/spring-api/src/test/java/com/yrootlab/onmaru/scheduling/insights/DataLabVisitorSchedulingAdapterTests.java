package com.yrootlab.onmaru.scheduling.insights;

import com.yrootlab.onmaru.insights.ingestion.DataLabVisitorIngestionService;
import com.yrootlab.onmaru.insights.ingestion.DataLabVisitorRevisionWriter;
import com.yrootlab.onmaru.insights.observation.ObservationCoverageStatus;
import com.yrootlab.onmaru.insights.observation.ObservationMetric;
import com.yrootlab.onmaru.insights.observation.SpatialLevel;
import com.yrootlab.onmaru.insights.observation.VisitorObservation;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DataLabVisitorSchedulingAdapterTests {

    @Test
    void runsTheIngestionServiceAtTheDefaultDailyKstSchedule() throws Exception {
        var writer = new RecordingWriter();
        var service = new DataLabVisitorIngestionService(
                () -> List.of(new VisitorObservation(
                        "KTO_DATALAB", "kr-45-jeonju", LocalDate.parse("2026-09-25"),
                        ObservationMetric.VISITOR_COUNT, 18_240L, "persons", SpatialLevel.SIGUNGU,
                        ObservationCoverageStatus.COMPLETE, Instant.parse("2026-09-26T00:00:00Z"))),
                writer);
        var adapter = new DataLabVisitorSchedulingAdapter(service);

        adapter.sync();

        assertThat(writer.replacements).hasSize(1);
        var scheduled = DataLabVisitorSchedulingAdapter.class
                .getMethod("sync")
                .getAnnotation(Scheduled.class);
        assertThat(scheduled.cron()).isEqualTo("${onmaru.datalab.visitor.sync.cron:0 30 3 * * *}");
        assertThat(scheduled.zone()).isEqualTo("Asia/Seoul");
    }

    private static final class RecordingWriter implements DataLabVisitorRevisionWriter {
        private final java.util.ArrayList<List<VisitorObservation>> replacements = new java.util.ArrayList<>();

        @Override
        public void replaceActive(List<VisitorObservation> observations) {
            replacements.add(List.copyOf(observations));
        }
    }
}
