package com.yrootlab.onmaru.web.insights;

import com.yrootlab.onmaru.catalog.region.CatalogRegionSourceCode;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DataLabVisitorSourceAdapterTests {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-25T18:30:00Z"), ZoneOffset.UTC);

    @Test
    void fetchesEachScopeOnceAndMapsOnlyOutsiderRecordsUsingCatalogSourceCodes() {
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
                (provider, dataset, asOf) -> List.of(
                        new CatalogRegionSourceCode("kr-45-jeonju", "SIGUNGU:52110"),
                        new CatalogRegionSourceCode("kr-45", "SIDO:52")),
                CLOCK,
                100);

        var observations = source.fetchDailyVisitorObservations();

        assertThat(requests).extracting(DataLabVisitorRequest::scope)
                .containsExactlyInAnyOrder(
                        DataLabVisitorRequest.Scope.LOCAL_GOVERNMENT,
                        DataLabVisitorRequest.Scope.METROPOLITAN);
        assertThat(requests).allSatisfy(request -> {
            assertThat(request.startDate()).isEqualTo(LocalDate.parse("2026-09-26"));
            assertThat(request.endDate()).isEqualTo(LocalDate.parse("2026-09-26"));
        });
        assertThat(observations).extracting(observation -> observation.regionCode(), observation -> observation.value())
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple("kr-45-jeonju", 18_240L),
                        org.assertj.core.groups.Tuple.tuple("kr-45", 18_240L));
        assertThat(observations).extracting(observation -> observation.regionCode(), observation -> observation.spatialLevel())
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple("kr-45-jeonju", SpatialLevel.SIGUNGU),
                        org.assertj.core.groups.Tuple.tuple("kr-45", SpatialLevel.SIDO));
    }

    @Test
    void rejectsAnUnscopedCatalogSourceCodeBeforeMappingResponses() {
        var source = new DataLabVisitorSourceAdapter(
                request -> List.of(),
                (provider, dataset, asOf) -> List.of(new CatalogRegionSourceCode("kr-45", "45")),
                CLOCK,
                100);

        assertThatThrownBy(source::fetchDailyVisitorObservations)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("source code");
    }

    @Test
    void rejectsPublishingWhenNoVerifiedRegionMappingIsActive() {
        var source = new DataLabVisitorSourceAdapter(
                request -> List.of(),
                (provider, dataset, asOf) -> List.of(),
                CLOCK,
                100);

        assertThatThrownBy(source::fetchDailyVisitorObservations)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No verified");
    }

    private DataLabVisitorRecord record(
            DataLabVisitorRequest.Scope scope,
            String code,
            String division,
            long count) {
        return new DataLabVisitorRecord(
                scope, LocalDate.parse("2026-09-26"), code, "전주시", division, "외지인", count);
    }
}
