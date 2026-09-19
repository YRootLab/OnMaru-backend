package com.yrootlab.onmaru.tourism.insights;

import com.fasterxml.jackson.databind.JsonNode;
import com.yrootlab.onmaru.insights.observation.ConcentrationObservation;
import com.yrootlab.onmaru.insights.observation.ObservationCoverageStatus;
import com.yrootlab.onmaru.insights.observation.ObservationMetric;
import com.yrootlab.onmaru.insights.observation.SpatialLevel;
import com.yrootlab.onmaru.insights.observation.VisitorObservation;

import java.time.Instant;
import java.time.LocalDate;

public final class DataLabObservationMapper {

    public VisitorObservation mapVisitorObservation(JsonNode payload) {
        Long visitorCount = nullableLong(payload.path("visitorCount"));
        return new VisitorObservation(
                requiredText(payload, "provider"),
                requiredText(payload, "regionCode"),
                LocalDate.parse(requiredText(payload, "basisDate")),
                ObservationMetric.VISITOR_COUNT,
                visitorCount,
                "persons",
                spatialLevel(payload.path("spatialLevel").asText("UNKNOWN")),
                visitorCount == null ? ObservationCoverageStatus.NOT_AVAILABLE : ObservationCoverageStatus.COMPLETE,
                Instant.parse(requiredText(payload, "sourceObservedAt"))
        );
    }

    public ConcentrationObservation mapConcentrationObservation(JsonNode payload) {
        Double score = nullableDouble(payload.path("congestionScore"));
        return new ConcentrationObservation(
                requiredText(payload, "provider"),
                targetKey(payload),
                nullableText(payload, "regionCode"),
                LocalDate.parse(requiredText(payload, "basisDate")),
                ObservationMetric.CONGESTION_SCORE,
                score,
                "score",
                score == null ? ObservationCoverageStatus.NOT_AVAILABLE : ObservationCoverageStatus.PARTIAL
        );
    }

    private String targetKey(JsonNode payload) {
        String targetKey = nullableText(payload, "targetKey");
        return targetKey == null ? "UNKNOWN" : targetKey;
    }

    private SpatialLevel spatialLevel(String value) {
        try {
            return SpatialLevel.valueOf(value);
        } catch (IllegalArgumentException exception) {
            return SpatialLevel.UNKNOWN;
        }
    }

    private Long nullableLong(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        return node.asLong();
    }

    private Double nullableDouble(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        return node.asDouble();
    }

    private String requiredText(JsonNode payload, String field) {
        String value = nullableText(payload, field);
        if (value == null) {
            throw new DataLabObservationMappingException(field);
        }
        return value;
    }

    private String nullableText(JsonNode payload, String field) {
        JsonNode node = payload.path(field);
        if (node.isMissingNode() || node.isNull() || node.asText().isBlank()) {
            return null;
        }
        return node.asText().trim();
    }
}
