package com.yrootlab.onmaru.insights.query;

import java.time.Clock;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;

public final class InsightsQueryService {

    private static final String SCHEMA_VERSION = "1.2";

    private final InMemoryInsightsQueryStore store;
    private final Clock clock;

    public InsightsQueryService(InMemoryInsightsQueryStore store, Clock clock) {
        this.store = store;
        this.clock = clock;
    }

    public ObservationPage listObservations(String regionCode, String metric, LocalDate from, LocalDate to) {
        List<Observation> items = store.observations().stream()
                .filter(item -> item.region().regionCode().equals(regionCode))
                .filter(item -> metric == null || item.metric().equals(metric))
                .filter(item -> from == null || !item.observedDate().isBefore(from))
                .filter(item -> to == null || !item.observedDate().isAfter(to))
                .sorted(Comparator.comparing(Observation::observedDate).reversed())
                .toList();
        return new ObservationPage(SCHEMA_VERSION, aggregateCoverage(items), clock.instant(), items);
    }

    public HeatmapResponse heatmap(String regionCode, LocalDate observedDate, String metric) {
        List<HeatSpot> spots = store.heatSpots().stream()
                .filter(spot -> regionCode == null || spot.region().regionCode().equals(regionCode))
                .filter(spot -> spot.observedDate().equals(observedDate))
                .filter(spot -> metric == null || spot.metric().equals(metric))
                .sorted(Comparator.comparing(HeatSpot::id))
                .toList();
        String resolvedMetric = metric == null ? "CONGESTION_SCORE" : metric;
        return new HeatmapResponse(SCHEMA_VERSION, aggregateCoverage(spots), resolvedMetric, observedDate, clock.instant(), spots);
    }

    private String aggregateCoverage(List<? extends Record> items) {
        if (items.isEmpty()) {
            return "MISSING";
        }
        if (items.stream().anyMatch(this::isMissing)) {
            return "MISSING";
        }
        if (items.stream().anyMatch(this::isStale)) {
            return "STALE";
        }
        if (items.stream().anyMatch(this::isPartial)) {
            return "PARTIAL";
        }
        return "COMPLETE";
    }

    private boolean isMissing(Record item) {
        if (item instanceof Observation observation) {
            return "MISSING".equals(observation.coverageStatus());
        }
        if (item instanceof HeatSpot spot) {
            return "MISSING".equals(spot.coverageStatus());
        }
        return false;
    }

    private boolean isStale(Record item) {
        if (item instanceof Observation observation) {
            return "STALE".equals(observation.coverageStatus());
        }
        if (item instanceof HeatSpot spot) {
            return "STALE".equals(spot.coverageStatus());
        }
        return false;
    }

    private boolean isPartial(Record item) {
        if (item instanceof Observation observation) {
            return "PARTIAL".equals(observation.coverageStatus());
        }
        if (item instanceof HeatSpot spot) {
            return "PARTIAL".equals(spot.coverageStatus());
        }
        return false;
    }
}
