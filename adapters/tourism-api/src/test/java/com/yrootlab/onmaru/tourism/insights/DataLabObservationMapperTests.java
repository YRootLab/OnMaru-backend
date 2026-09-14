package com.yrootlab.onmaru.tourism.insights;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yrootlab.onmaru.insights.observation.ObservationCoverageStatus;
import com.yrootlab.onmaru.insights.observation.ObservationMetric;
import com.yrootlab.onmaru.insights.observation.SpatialLevel;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class DataLabObservationMapperTests {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final DataLabObservationMapper mapper = new DataLabObservationMapper();

    @Test
    void mapsVisitorObservationWithBasisDateSpatialLevelSourceAndStatus() throws IOException {
        var payload = objectMapper.readTree("""
                {
                  "provider": "kto-datalab",
                  "basisDate": "2026-09-14",
                  "regionCode": "kr-45-jeonju",
                  "spatialLevel": "SIGUNGU",
                  "visitorCount": 18240,
                  "sourceObservedAt": "2026-09-15T02:30:00Z"
                }
                """);

        var observation = mapper.mapVisitorObservation(payload);

        assertThat(observation.provider()).isEqualTo("kto-datalab");
        assertThat(observation.regionCode()).isEqualTo("kr-45-jeonju");
        assertThat(observation.basisDate()).isEqualTo(LocalDate.parse("2026-09-14"));
        assertThat(observation.metric()).isEqualTo(ObservationMetric.VISITOR_COUNT);
        assertThat(observation.value()).isEqualTo(18240L);
        assertThat(observation.unit()).isEqualTo("persons");
        assertThat(observation.spatialLevel()).isEqualTo(SpatialLevel.SIGUNGU);
        assertThat(observation.coverageStatus()).isEqualTo(ObservationCoverageStatus.COMPLETE);
    }

    @Test
    void keepsMissingVisitorCountAsNotAvailableInsteadOfZero() throws IOException {
        var payload = objectMapper.readTree("""
                {
                  "provider": "kto-datalab",
                  "basisDate": "2026-09-14",
                  "regionCode": "kr-45-jeonju",
                  "spatialLevel": "SIGUNGU",
                  "visitorCount": null,
                  "sourceObservedAt": "2026-09-15T02:30:00Z"
                }
                """);

        var observation = mapper.mapVisitorObservation(payload);

        assertThat(observation.value()).isNull();
        assertThat(observation.coverageStatus()).isEqualTo(ObservationCoverageStatus.NOT_AVAILABLE);
        assertThat(observation.unit()).isEqualTo("persons");
    }

    @Test
    void mapsUnknownTargetWhenProviderTargetCannotBeLinked() throws IOException {
        var payload = objectMapper.readTree("""
                {
                  "provider": "kto-datalab",
                  "basisDate": "2026-09-14",
                  "targetName": "전주 한옥마을",
                  "regionCode": "kr-45-jeonju",
                  "congestionScore": 72.4
                }
                """);

        var observation = mapper.mapConcentrationObservation(payload);

        assertThat(observation.targetKey()).isEqualTo("UNKNOWN");
        assertThat(observation.metric()).isEqualTo(ObservationMetric.CONGESTION_SCORE);
        assertThat(observation.value()).isEqualTo(72.4);
        assertThat(observation.unit()).isEqualTo("score");
        assertThat(observation.coverageStatus()).isEqualTo(ObservationCoverageStatus.PARTIAL);
    }
}
