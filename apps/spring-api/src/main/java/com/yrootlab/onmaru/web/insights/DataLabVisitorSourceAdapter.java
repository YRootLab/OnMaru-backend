package com.yrootlab.onmaru.web.insights;

import com.yrootlab.onmaru.catalog.region.DataLabRegionMapping;
import com.yrootlab.onmaru.catalog.region.DataLabRegionMappingRegistry;
import com.yrootlab.onmaru.catalog.region.DataLabRegionMappingStatus;
import com.yrootlab.onmaru.insights.ingestion.DataLabCollectionExclusion;
import com.yrootlab.onmaru.insights.ingestion.DataLabCollectionReason;
import com.yrootlab.onmaru.insights.ingestion.DataLabVisitorFetchResult;
import com.yrootlab.onmaru.insights.ingestion.DataLabVisitorSource;
import com.yrootlab.onmaru.insights.observation.ObservationCoverageStatus;
import com.yrootlab.onmaru.insights.observation.ObservationMetric;
import com.yrootlab.onmaru.insights.observation.SpatialLevel;
import com.yrootlab.onmaru.insights.observation.VisitorObservation;
import com.yrootlab.onmaru.tourism.insights.DataLabVisitorClient;
import com.yrootlab.onmaru.tourism.insights.DataLabVisitorRecord;
import com.yrootlab.onmaru.tourism.insights.DataLabVisitorRequest;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Maps only ACTIVE registry entries to observations and quarantines unsafe batches. */
final class DataLabVisitorSourceAdapter implements DataLabVisitorSource {

    private static final String OUTSIDER_DIVISION_CODE = "2";
    private static final ZoneId KOREA_STANDARD_TIME = ZoneId.of("Asia/Seoul");

    private final DataLabVisitorRecordFetcher recordFetcher;
    private final DataLabRegionMappingRegistry mappingRegistry;
    private final Clock clock;
    private final int pageSize;

    DataLabVisitorSourceAdapter(
            DataLabVisitorClient client,
            DataLabRegionMappingRegistry mappingRegistry,
            Clock clock,
            int pageSize) {
        this(client::fetchAll, mappingRegistry, clock, pageSize);
    }

    DataLabVisitorSourceAdapter(
            DataLabVisitorRecordFetcher recordFetcher,
            DataLabRegionMappingRegistry mappingRegistry,
            Clock clock,
            int pageSize) {
        this.recordFetcher = Objects.requireNonNull(recordFetcher);
        this.mappingRegistry = Objects.requireNonNull(mappingRegistry);
        this.clock = Objects.requireNonNull(clock);
        if (pageSize < 1 || pageSize > 1_000) {
            throw new IllegalArgumentException("DataLab pageSize must be between 1 and 1000");
        }
        this.pageSize = pageSize;
    }

    @Override
    public DataLabVisitorFetchResult fetchDailyVisitorObservations() {
        Instant observedAt = clock.instant();
        LocalDate basisDate = LocalDate.now(clock.withZone(KOREA_STANDARD_TIME));
        var exclusions = new ArrayList<DataLabCollectionExclusion>();
        var activeMappings = activeMappings(basisDate, exclusions);
        boolean invalidRegistry = exclusions.stream().anyMatch(exclusion ->
                exclusion.reason() == DataLabCollectionReason.INVALID_MAPPING);
        if (activeMappings.isEmpty()) {
            exclusions.add(new DataLabCollectionExclusion(null, DataLabCollectionReason.NO_ACTIVE_MAPPING));
            return new DataLabVisitorFetchResult(List.of(), exclusions, invalidRegistry);
        }

        var observations = new LinkedHashMap<String, VisitorObservation>();
        boolean quarantined = invalidRegistry;
        for (DataLabVisitorRequest.Scope scope : requestedScopes(activeMappings.keySet())) {
            try {
                for (DataLabVisitorRecord record : recordFetcher.fetch(request(scope, basisDate))) {
                    if (record == null || record.scope() != scope) {
                        exclusions.add(new DataLabCollectionExclusion(
                                null, DataLabCollectionReason.RESPONSE_SCOPE_MISMATCH));
                        quarantined = true;
                        continue;
                    }
                    if (!basisDate.equals(record.basisDate())) {
                        exclusions.add(new DataLabCollectionExclusion(
                                null, DataLabCollectionReason.RESPONSE_BASIS_DATE_MISMATCH));
                        quarantined = true;
                        continue;
                    }
                    if (!OUTSIDER_DIVISION_CODE.equals(record.visitorDivisionCode())) {
                        continue;
                    }
                    DataLabRegionMapping mapping = activeMappings.get(
                            new ScopedCode(regionLevel(scope), record.providerRegionCode()));
                    if (mapping == null) {
                        continue;
                    }
                    if (observations.containsKey(mapping.internalRegionCode())) {
                        exclusions.add(new DataLabCollectionExclusion(
                                mapping.internalRegionCode(), DataLabCollectionReason.DUPLICATE_RESPONSE));
                        quarantined = true;
                        continue;
                    }
                    try {
                        observations.put(mapping.internalRegionCode(),
                                observation(mapping, record, observedAt));
                    } catch (IllegalArgumentException exception) {
                        exclusions.add(new DataLabCollectionExclusion(
                                mapping.internalRegionCode(), DataLabCollectionReason.INVALID_PROVIDER_VALUE));
                        quarantined = true;
                    }
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                exclusions.add(new DataLabCollectionExclusion(null, DataLabCollectionReason.PROVIDER_FAILURE));
                quarantined = true;
            } catch (RuntimeException exception) {
                exclusions.add(new DataLabCollectionExclusion(null, DataLabCollectionReason.PROVIDER_FAILURE));
                quarantined = true;
            }
        }

        Set<String> observedRegions = observations.keySet();
        activeMappings.values().stream()
                .map(DataLabRegionMapping::internalRegionCode)
                .distinct()
                .filter(region -> !observedRegions.contains(region))
                .forEach(region -> exclusions.add(new DataLabCollectionExclusion(
                        region, DataLabCollectionReason.MISSING_ACTIVE_REGION)));
        if (exclusions.stream().anyMatch(exclusion ->
                exclusion.reason() == DataLabCollectionReason.MISSING_ACTIVE_REGION)) {
            quarantined = true;
        }
        return new DataLabVisitorFetchResult(
                quarantined ? List.of() : List.copyOf(observations.values()),
                exclusions,
                quarantined);
    }

    private Map<ScopedCode, DataLabRegionMapping> activeMappings(
            LocalDate basisDate,
            List<DataLabCollectionExclusion> exclusions) {
        var active = new LinkedHashMap<ScopedCode, DataLabRegionMapping>();
        for (DataLabRegionMapping mapping : mappingRegistry.findCurrent(basisDate)) {
            if (mapping.status() == DataLabRegionMappingStatus.PENDING) {
                exclusions.add(new DataLabCollectionExclusion(
                        mapping.internalRegionCode(), DataLabCollectionReason.PENDING_MAPPING));
                continue;
            }
            if (mapping.status() == DataLabRegionMappingStatus.REJECTED) {
                exclusions.add(new DataLabCollectionExclusion(
                        mapping.internalRegionCode(), DataLabCollectionReason.REJECTED_MAPPING));
                continue;
            }
            ScopedCode key = scopedCode(mapping);
            DataLabRegionMapping previous = active.putIfAbsent(key, mapping);
            if (previous != null) {
                exclusions.add(new DataLabCollectionExclusion(
                        mapping.internalRegionCode(), DataLabCollectionReason.INVALID_MAPPING));
                active.remove(key);
            }
        }
        return Map.copyOf(active);
    }

    private EnumSet<DataLabVisitorRequest.Scope> requestedScopes(Set<ScopedCode> codes) {
        var scopes = EnumSet.noneOf(DataLabVisitorRequest.Scope.class);
        for (ScopedCode code : codes) {
            scopes.add(code.level() == DataLabRegionMapping.Level.SIDO
                    ? DataLabVisitorRequest.Scope.METROPOLITAN
                    : DataLabVisitorRequest.Scope.LOCAL_GOVERNMENT);
        }
        return scopes;
    }

    private DataLabVisitorRequest request(DataLabVisitorRequest.Scope scope, LocalDate basisDate) {
        return switch (scope) {
            case METROPOLITAN -> DataLabVisitorRequest.metropolitan(basisDate, basisDate, pageSize);
            case LOCAL_GOVERNMENT -> DataLabVisitorRequest.localGovernment(basisDate, basisDate, pageSize);
        };
    }

    private ScopedCode scopedCode(DataLabRegionMapping mapping) {
        int separator = mapping.dataLabRegionCode().indexOf(':');
        if (separator < 1 || separator == mapping.dataLabRegionCode().length() - 1) {
            throw new IllegalArgumentException("invalid DataLab registry source code");
        }
        return new ScopedCode(mapping.level(), mapping.dataLabRegionCode().substring(separator + 1));
    }

    private VisitorObservation observation(
            DataLabRegionMapping mapping,
            DataLabVisitorRecord record,
            Instant observedAt) {
        Long count = record.visitorCount();
        if (count != null && count < 0) {
            throw new IllegalArgumentException("DataLab visitor count must not be negative");
        }
        return new VisitorObservation(
                "KTO_DATALAB",
                mapping.internalRegionCode(),
                record.basisDate(),
                ObservationMetric.VISITOR_COUNT,
                count,
                "persons",
                mapping.level() == DataLabRegionMapping.Level.SIDO ? SpatialLevel.SIDO : SpatialLevel.SIGUNGU,
                count == null ? ObservationCoverageStatus.NOT_AVAILABLE : ObservationCoverageStatus.COMPLETE,
                observedAt);
    }

    private DataLabRegionMapping.Level regionLevel(DataLabVisitorRequest.Scope scope) {
        return scope == DataLabVisitorRequest.Scope.METROPOLITAN
                ? DataLabRegionMapping.Level.SIDO
                : DataLabRegionMapping.Level.SIGUNGU;
    }

    @FunctionalInterface
    interface DataLabVisitorRecordFetcher {
        List<DataLabVisitorRecord> fetch(DataLabVisitorRequest request) throws InterruptedException;
    }

    private record ScopedCode(DataLabRegionMapping.Level level, String providerCode) {
    }
}
