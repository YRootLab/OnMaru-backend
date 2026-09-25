package com.yrootlab.onmaru.web.insights;

import com.yrootlab.onmaru.catalog.region.CatalogRegionSourceCode;
import com.yrootlab.onmaru.catalog.region.CatalogRegionSourceCodeLookup;
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
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Maps Catalog's scoped DataLab source codes into outsider-only visitor observations. */
final class DataLabVisitorSourceAdapter implements DataLabVisitorSource {

    static final String PROVIDER = "KTO_DATALAB";
    static final String DATASET = "visitor";
    private static final String OUTSIDER_DIVISION_CODE = "2";
    private static final ZoneId KOREA_STANDARD_TIME = ZoneId.of("Asia/Seoul");

    private final DataLabVisitorRecordFetcher recordFetcher;
    private final CatalogRegionSourceCodeLookup regionSourceCodes;
    private final Clock clock;
    private final int pageSize;

    DataLabVisitorSourceAdapter(
            DataLabVisitorClient client,
            CatalogRegionSourceCodeLookup regionSourceCodes,
            Clock clock,
            int pageSize) {
        this(client::fetchAll, regionSourceCodes, clock, pageSize);
    }

    DataLabVisitorSourceAdapter(
            DataLabVisitorRecordFetcher recordFetcher,
            CatalogRegionSourceCodeLookup regionSourceCodes,
            Clock clock,
            int pageSize) {
        this.recordFetcher = Objects.requireNonNull(recordFetcher);
        this.regionSourceCodes = Objects.requireNonNull(regionSourceCodes);
        this.clock = Objects.requireNonNull(clock);
        if (pageSize < 1 || pageSize > 1_000) {
            throw new IllegalArgumentException("DataLab pageSize must be between 1 and 1000");
        }
        this.pageSize = pageSize;
    }

    @Override
    public List<VisitorObservation> fetchDailyVisitorObservations() {
        Instant observedAt = clock.instant();
        LocalDate basisDate = LocalDate.now(clock.withZone(KOREA_STANDARD_TIME));
        var observations = new ArrayList<VisitorObservation>();
        Map<ScopedCode, String> catalogRegionCodes = catalogRegionCodes(basisDate);
        if (catalogRegionCodes.isEmpty()) {
            throw new IllegalStateException("No verified DataLab visitor region mappings are active");
        }
        for (DataLabVisitorRequest.Scope scope : DataLabVisitorRequest.Scope.values()) {
            var request = request(scope, basisDate);
            try {
                for (DataLabVisitorRecord record : recordFetcher.fetch(request)) {
                    if (record == null || record.scope() != scope) {
                        throw new IllegalStateException("DataLab visitor response scope does not match request");
                    }
                    if (OUTSIDER_DIVISION_CODE.equals(record.visitorDivisionCode())) {
                        String regionCode = catalogRegionCodes.get(new ScopedCode(regionScope(scope), record.providerRegionCode()));
                        if (regionCode != null) {
                            observations.add(observation(regionCode, scope, record, observedAt));
                        }
                    }
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("DataLab visitor fetch was interrupted", exception);
            }
        }
        var observedRegions = observations.stream().map(VisitorObservation::regionCode).collect(java.util.stream.Collectors.toSet());
        var missingRegions = catalogRegionCodes.values().stream().filter(region -> !observedRegions.contains(region)).toList();
        if (!missingRegions.isEmpty()) {
            throw new IllegalStateException("DataLab visitor response is incomplete for verified regions: " + missingRegions);
        }
        return List.copyOf(observations);
    }

    private Map<ScopedCode, String> catalogRegionCodes(LocalDate basisDate) {
        var mappings = new LinkedHashMap<ScopedCode, String>();
        for (CatalogRegionSourceCode sourceCode : regionSourceCodes.findCurrent(PROVIDER, DATASET, basisDate)) {
            ScopedCode scopedCode = parseScopedCode(sourceCode.sourceCode());
            String existing = mappings.putIfAbsent(scopedCode, sourceCode.regionCode());
            if (existing != null && !existing.equals(sourceCode.regionCode())) {
                throw new IllegalStateException("DataLab source code is mapped to multiple Catalog regions: " + sourceCode.sourceCode());
            }
        }
        return Map.copyOf(mappings);
    }

    private DataLabVisitorRequest request(DataLabVisitorRequest.Scope scope, LocalDate basisDate) {
        return switch (scope) {
            case METROPOLITAN -> DataLabVisitorRequest.metropolitan(basisDate, basisDate, pageSize);
            case LOCAL_GOVERNMENT -> DataLabVisitorRequest.localGovernment(basisDate, basisDate, pageSize);
        };
    }

    private ScopedCode parseScopedCode(String encodedSourceCode) {
        if (encodedSourceCode == null) {
            throw invalidSourceCode(null);
        }
        int separator = encodedSourceCode.indexOf(':');
        if (separator <= 0 || separator != encodedSourceCode.lastIndexOf(':')
                || separator == encodedSourceCode.length() - 1) {
            throw invalidSourceCode(encodedSourceCode);
        }
        String scope = encodedSourceCode.substring(0, separator).trim();
        String providerCode = encodedSourceCode.substring(separator + 1).trim();
        return switch (scope) {
            case "SIDO" -> new ScopedCode(RegionScope.SIDO, providerCode);
            case "SIGUNGU" -> new ScopedCode(RegionScope.SIGUNGU, providerCode);
            default -> throw invalidSourceCode(encodedSourceCode);
        };
    }

    private VisitorObservation observation(
            String regionCode,
            DataLabVisitorRequest.Scope scope,
            DataLabVisitorRecord record,
            Instant observedAt) {
        Long count = record.visitorCount();
        if (count != null && count < 0) {
            throw new IllegalStateException("DataLab visitor count must not be negative");
        }
        return new VisitorObservation(
                PROVIDER,
                regionCode,
                record.basisDate(),
                ObservationMetric.VISITOR_COUNT,
                count,
                "persons",
                scope == DataLabVisitorRequest.Scope.METROPOLITAN ? SpatialLevel.SIDO : SpatialLevel.SIGUNGU,
                count == null ? ObservationCoverageStatus.NOT_AVAILABLE : ObservationCoverageStatus.COMPLETE,
                observedAt);
    }

    private RegionScope regionScope(DataLabVisitorRequest.Scope scope) {
        return scope == DataLabVisitorRequest.Scope.METROPOLITAN ? RegionScope.SIDO : RegionScope.SIGUNGU;
    }

    private IllegalArgumentException invalidSourceCode(String sourceCode) {
        return new IllegalArgumentException("DataLab source code must use SIDO:<code> or SIGUNGU:<code>: " + sourceCode);
    }

    @FunctionalInterface
    interface DataLabVisitorRecordFetcher {
        List<DataLabVisitorRecord> fetch(DataLabVisitorRequest request) throws InterruptedException;
    }

    private enum RegionScope {
        SIDO,
        SIGUNGU
    }

    private record ScopedCode(RegionScope scope, String providerCode) {
    }
}
