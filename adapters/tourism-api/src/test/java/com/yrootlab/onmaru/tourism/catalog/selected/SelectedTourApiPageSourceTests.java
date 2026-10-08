package com.yrootlab.onmaru.tourism.catalog.selected;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yrootlab.onmaru.catalog.application.sourcefetch.SelectedSourceFetch;
import com.yrootlab.onmaru.tourism.catalog.client.TourApiError;
import com.yrootlab.onmaru.tourism.catalog.client.TourApiOutcomeKind;
import com.yrootlab.onmaru.tourism.catalog.client.TourApiPage;
import com.yrootlab.onmaru.tourism.catalog.client.TourApiParseResult;
import com.yrootlab.onmaru.tourism.catalog.client.TourApiSourceRecord;
import com.yrootlab.onmaru.tourism.catalog.client.TourApiUriBuilder;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SelectedTourApiPageSourceTests {
    private final TourApiUriBuilder uris = new TourApiUriBuilder(
            URI.create("https://example.test/KorService2"), "fake-key", "OnMaru");

    @Test
    void usesSelectedClassificationAndPreservesRawModifiedTime() throws Exception {
        var json = new ObjectMapper().readTree("""
                {"contentid":"126508","lclsSystm3":"HS010100","modifiedtime":"20261008010101"}
                """);
        var adapter = new SelectedTourApiPageSource((operation, uri) -> {
            assertThat(operation).isEqualTo("areaBasedList2");
            assertThat(uri.getRawQuery()).contains("lclsSystm3=HS010100", "pageNo=1", "numOfRows=2");
            return TourApiParseResult.success(new TourApiPage(operation, 1, 2, 1, true,
                    List.of(new TourApiSourceRecord(operation, json))));
        }, uris);
        var page = adapter.fetch(new SelectedSourceFetch.Query("areaBasedList2", "HS010100"), 1, 2);
        assertThat(page.records().getFirst().field("modifiedtime")).isEqualTo("20261008010101");
    }

    @Test
    void exhaustedRateLimitFailsTheRun() {
        var adapter = new SelectedTourApiPageSource((operation, uri) -> TourApiParseResult.error(
                new TourApiError(operation, TourApiOutcomeKind.RATE_LIMITED, "22", "limit", true, null)), uris);
        assertThatThrownBy(() -> adapter.fetch(new SelectedSourceFetch.Query("searchKeyword2", "한옥"), 1, 2))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("RATE_LIMITED");
    }
}
