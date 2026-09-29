package com.yrootlab.onmaru.insights.query;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class InsightsQueryService {

    private static final String SCHEMA_VERSION = "1.2";

    private final InsightsQueryStore store;
    private final Clock clock;

    public InsightsQueryService(InsightsQueryStore store, Clock clock) {
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
        List<HeatSpot> candidates = store.heatSpots().stream()
                .filter(spot -> regionCode == null || spot.region().regionCode().equals(regionCode))
                .filter(spot -> metric == null || spot.metric().equals(metric))
                .toList();
        LocalDate resolvedDate = observedDate == null
                ? candidates.stream().map(HeatSpot::observedDate).max(LocalDate::compareTo)
                        .orElse(LocalDate.now(clock))
                : observedDate;
        List<HeatSpot> spots = candidates.stream()
                .filter(spot -> spot.observedDate().equals(resolvedDate))
                .sorted(Comparator.comparing(HeatSpot::id))
                .toList();
        String resolvedMetric = metric == null ? "CONGESTION_SCORE" : metric;
        return new HeatmapResponse(SCHEMA_VERSION, aggregateCoverage(spots), resolvedMetric, resolvedDate, clock.instant(), spots);
    }

    public MapHeatResponse mapHeat(double lat, double lng, double radiusMeters) {
        return mapHeat(lat, lng, 7, radiusMeters);
    }

    public MapHeatResponse mapHeat(double lat, double lng, int level, double radiusMeters) {
        double effectiveRadius = Math.max(radiusMeters, level <= 5 ? 5_000.0 : 15_000.0);
        List<HeatSpot> candidates = store.heatSpots().stream()
                .filter(this::hasCompleteHeatValues)
                .filter(spot -> distanceMeters(lat, lng, spot.coordinates().lat(), spot.coordinates().lng()) <= effectiveRadius)
                .sorted(Comparator.comparing(HeatSpot::observedDate))
                .toList();
        if (candidates.isEmpty()) {
            return new MapHeatResponse(List.of(), List.of(), 0, clock.instant(),
                    "DERIVED_INDEX", "MISSING", "SIGUNGU", null, null, "warmth-v2");
        }

        Map<String, List<HeatSpot>> seriesByPlace = candidates.stream()
                .collect(java.util.stream.Collectors.groupingBy(
                        HeatSpot::placeId, LinkedHashMap::new, java.util.stream.Collectors.toList()));
        List<LocalDate> dates = candidates.stream()
                .map(HeatSpot::observedDate)
                .distinct()
                .sorted()
                .toList();
        List<MapHeatDay> days = dates.stream()
                .map(date -> new MapHeatDay(ymd(date), koreanWeekday(date.getDayOfWeek())))
                .toList();
        var generatedAt = clock.instant();
        List<MapHeatSpot> spots = seriesByPlace.values().stream()
                .map(series -> toMapHeatSpot(series, dates, generatedAt))
                .sorted(Comparator.comparing(MapHeatSpot::id))
                .toList();
        LocalDate observedFrom = dates.stream().min(LocalDate::compareTo).orElse(null);
        LocalDate observedTo = dates.stream().max(LocalDate::compareTo).orElse(null);
        return new MapHeatResponse(spots, days, spots.size(), generatedAt,
                "DERIVED_INDEX", aggregateCoverage(candidates), "SIGUNGU",
                observedFrom, observedTo, "warmth-v2");
    }

    private MapHeatSpot toMapHeatSpot(List<HeatSpot> series, List<LocalDate> dates, java.time.Instant generatedAt) {
        HeatSpot latest = series.stream().max(Comparator.comparing(HeatSpot::observedDate)).orElseThrow();
        Map<LocalDate, Double> scores = series.stream().collect(java.util.stream.Collectors.toMap(
                HeatSpot::observedDate, HeatSpot::congestionScore, (left, right) -> right));
        return new MapHeatSpot(
                latest.id(), latest.placeId(), latest.name(),
                latest.coordinates().lat(), latest.coordinates().lng(), latest.region().name(),
                latest.visitorCount(), latest.congestionScore(), latest.congestionLevel().toLowerCase(Locale.ROOT),
                latest.surgeMultiplier(), Math.min(1.0, Math.max(0.25, latest.congestionScore() / 100.0)),
                dates.stream().map(date -> scores.getOrDefault(date, 0.0)).toList(), generatedAt);
    }

    private boolean hasCompleteHeatValues(HeatSpot spot) {
        return spot != null
                && spot.placeId() != null
                && spot.region() != null
                && spot.coordinates() != null
                && spot.visitorCount() != null
                && spot.congestionScore() != null
                && spot.congestionLevel() != null
                && spot.surgeMultiplier() != null
                && spot.observedDate() != null
                && !"MISSING".equals(spot.coverageStatus());
    }

    private double distanceMeters(double lat1, double lng1, double lat2, double lng2) {
        double latDelta = Math.toRadians(lat2 - lat1);
        double lngDelta = Math.toRadians(lng2 - lng1);
        double a = Math.sin(latDelta / 2) * Math.sin(latDelta / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(lngDelta / 2) * Math.sin(lngDelta / 2);
        return 6_371_000.0 * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    private String ymd(LocalDate date) {
        return "%04d%02d%02d".formatted(date.getYear(), date.getMonthValue(), date.getDayOfMonth());
    }

    private String koreanWeekday(DayOfWeek dayOfWeek) {
        return switch (dayOfWeek) {
            case MONDAY -> "월요일";
            case TUESDAY -> "화요일";
            case WEDNESDAY -> "수요일";
            case THURSDAY -> "목요일";
            case FRIDAY -> "금요일";
            case SATURDAY -> "토요일";
            case SUNDAY -> "일요일";
        };
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
