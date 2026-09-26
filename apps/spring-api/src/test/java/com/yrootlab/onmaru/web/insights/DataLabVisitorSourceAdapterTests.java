package com.yrootlab.onmaru.web.insights;

import com.yrootlab.onmaru.catalog.region.DataLabRegionMapping;
import com.yrootlab.onmaru.catalog.region.DataLabRegionMappingStatus;
import com.yrootlab.onmaru.insights.ingestion.DataLabCollectionReason;
import com.yrootlab.onmaru.insights.observation.ObservationCoverageStatus;
import com.yrootlab.onmaru.insights.observation.SpatialLevel;
import com.yrootlab.onmaru.tourism.insights.DataLabVisitorRecord;
import com.yrootlab.onmaru.tourism.insights.DataLabVisitorRequest;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class DataLabVisitorSourceAdapterTests {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-25T18:30:00Z"), ZoneOffset.UTC);

    @Test
    void fetchesEachScopeOnceAndMapsOnlyOutsiderRecordsUsingActiveRegistryMappings() {
        var requests = new ArrayList<DataLabVisitorRequest>();
        var source = new DataLabVisitorSourceAdapter(
                request -> {
                    requests.add(request);
                    return request.scope() == DataLabVisitorRequest.Scope.LOCAL_GOVERNMENT
                            ? List.of(
                            record(request.scope(), "52110", "2", 18_240L),
                            record(request.scope(), "52110", "3", 310L),
                            record(request.scope(), "99999", "2", 1L))
                            : List.of(record(request.scope(), "52", "2", 18_240L));
                },
                asOf -> List.of(
                        active("kr-45-jeonju", "SIGUNGU:52110", DataLabRegionMapping.Level.SIGUNGU, "전주시"),
                        active("kr-45", "SIDO:52", DataLabRegionMapping.Level.SIDO, "전북특별자치도")),
                CLOCK,
                100);

        var result = source.fetchDailyVisitorObservations();

        assertThat(result.publishable()).isTrue();
        assertThat(result.exclusions()).isEmpty();
        assertThat(requests).extracting(DataLabVisitorRequest::scope)
                .containsExactlyInAnyOrder(
                        DataLabVisitorRequest.Scope.LOCAL_GOVERNMENT,
                        DataLabVisitorRequest.Scope.METROPOLITAN);
        assertThat(result.observations())
                .extracting(observation -> observation.regionCode(), observation -> observation.value())
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple("kr-45-jeonju", 18_240L),
                        org.assertj.core.groups.Tuple.tuple("kr-45", 18_240L));
        assertThat(result.observations())
                .extracting(observation -> observation.regionCode(), observation -> observation.spatialLevel())
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple("kr-45-jeonju", SpatialLevel.SIGUNGU),
                        org.assertj.core.groups.Tuple.tuple("kr-45", SpatialLevel.SIDO));
    }

    @Test
    void skipsPendingAndRejectedMappingsWithoutCallingTheProvider() {
        var fetchCalls = new AtomicInteger();
        var source = new DataLabVisitorSourceAdapter(
                request -> {
                    fetchCalls.incrementAndGet();
                    return List.of();
                },
                asOf -> List.of(
                        mapping("kr-48", "SIDO:48", DataLabRegionMapping.Level.SIDO, "경상남도", DataLabRegionMappingStatus.PENDING),
                        mapping("kr-50", "SIDO:50", DataLabRegionMapping.Level.SIDO, "제주특별자치도", DataLabRegionMappingStatus.REJECTED)),
                CLOCK,
                100);

        var result = source.fetchDailyVisitorObservations();

        assertThat(fetchCalls).hasValue(0);
        assertThat(result.publishable()).isFalse();
        assertThat(result.exclusions()).extracting(exclusion -> exclusion.reason())
                .containsExactly(
                        DataLabCollectionReason.PENDING_MAPPING,
                        DataLabCollectionReason.REJECTED_MAPPING,
                        DataLabCollectionReason.NO_ACTIVE_MAPPING);
    }

    @Test
    void preservesProviderMissingValueAsNullAndNotAvailable() {
        var source = new DataLabVisitorSourceAdapter(
                request -> request.scope() == DataLabVisitorRequest.Scope.METROPOLITAN
                        ? List.of(record(request.scope(), "52", "2", null))
                        : List.of(),
                asOf -> List.of(active(
                        "kr-45", "SIDO:52", DataLabRegionMapping.Level.SIDO, "전북특별자치도")),
                CLOCK,
                100);

        var result = source.fetchDailyVisitorObservations();

        assertThat(result.publishable()).isTrue();
        assertThat(result.observations()).singleElement().satisfies(observation -> {
            assertThat(observation.value()).isNull();
            assertThat(observation.coverageStatus()).isEqualTo(ObservationCoverageStatus.NOT_AVAILABLE);
        });
    }

    @Test
    void quarantinesDuplicateMatchedProviderRowsWithoutPublishingEitherValue() {
        var source = new DataLabVisitorSourceAdapter(
                request -> request.scope() == DataLabVisitorRequest.Scope.METROPOLITAN
                        ? List.of(
                        record(request.scope(), "52", "2", 10L),
                        record(request.scope(), "52", "2", 20L))
                        : List.of(),
                asOf -> List.of(active(
                        "kr-45", "SIDO:52", DataLabRegionMapping.Level.SIDO, "전북특별자치도")),
                CLOCK,
                100);

        var result = source.fetchDailyVisitorObservations();

        assertThat(result.publishable()).isFalse();
        assertThat(result.observations()).isEmpty();
        assertThat(result.exclusions()).extracting(exclusion -> exclusion.reason())
                .contains(DataLabCollectionReason.DUPLICATE_RESPONSE);
    }

    @Test
    void quarantinesScopeMismatchAndMissingActiveMappings() {
        var source = new DataLabVisitorSourceAdapter(
                request -> request.scope() == DataLabVisitorRequest.Scope.METROPOLITAN
                        ? List.of(record(DataLabVisitorRequest.Scope.LOCAL_GOVERNMENT, "52", "2", 10L))
                        : List.of(),
                asOf -> List.of(active(
                        "kr-45", "SIDO:52", DataLabRegionMapping.Level.SIDO, "전북특별자치도")),
                CLOCK,
                100);

        var result = source.fetchDailyVisitorObservations();

        assertThat(result.publishable()).isFalse();
        assertThat(result.observations()).isEmpty();
        assertThat(result.exclusions()).extracting(exclusion -> exclusion.reason())
                .contains(DataLabCollectionReason.RESPONSE_SCOPE_MISMATCH,
                        DataLabCollectionReason.MISSING_ACTIVE_REGION);
    }

    @Test
    void quarantinesTheBatchWhenTheRegistryContainsDuplicateActiveSourceCodes() {
        var fetchCalls = new AtomicInteger();
        var source = new DataLabVisitorSourceAdapter(
                request -> {
                    fetchCalls.incrementAndGet();
                    return List.of();
                },
                asOf -> List.of(
                        active("kr-45", "SIDO:52", DataLabRegionMapping.Level.SIDO, "전북특별자치도"),
                        active("kr-48", "SIDO:52", DataLabRegionMapping.Level.SIDO, "경상남도")),
                CLOCK,
                100);

        var result = source.fetchDailyVisitorObservations();

        assertThat(fetchCalls).hasValue(0);
        assertThat(result.publishable()).isFalse();
        assertThat(result.quarantined()).isTrue();
        assertThat(result.exclusions()).extracting(exclusion -> exclusion.reason())
                .contains(DataLabCollectionReason.INVALID_MAPPING);
    }

    private DataLabRegionMapping active(
            String internalCode,
            String sourceCode,
            DataLabRegionMapping.Level level,
            String name) {
        return new DataLabRegionMapping(
                internalCode, sourceCode, level, name,
                "https://www.data.go.kr/data/15101972/openapi.do",
                Instant.parse("2026-09-26T00:00:00Z"),
                "catalog-operator",
                Instant.parse("2026-09-26T00:00:00Z"),
                DataLabRegionMappingStatus.ACTIVE);
    }

    private DataLabRegionMapping mapping(
            String internalCode,
            String sourceCode,
            DataLabRegionMapping.Level level,
            String name,
            DataLabRegionMappingStatus status) {
        return new DataLabRegionMapping(
                internalCode, sourceCode, level, name,
                null, null, null, null, status);
    }

    private DataLabVisitorRecord record(
            DataLabVisitorRequest.Scope scope,
            String code,
            String division,
            Long count) {
        return new DataLabVisitorRecord(
                scope, LocalDate.parse("2026-09-26"), code, "provider-region", division, "외지인", count);
    }
}
