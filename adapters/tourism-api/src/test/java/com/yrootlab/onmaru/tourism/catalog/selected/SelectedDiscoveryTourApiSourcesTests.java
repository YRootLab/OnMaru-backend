package com.yrootlab.onmaru.tourism.catalog.selected;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yrootlab.onmaru.catalog.application.qualification.SourceRecord;
import com.yrootlab.onmaru.tourism.catalog.client.TourApiPage;
import com.yrootlab.onmaru.tourism.catalog.client.TourApiParseResult;
import com.yrootlab.onmaru.tourism.catalog.client.TourApiSourceRecord;
import com.yrootlab.onmaru.tourism.catalog.client.TourApiUriBuilder;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SelectedDiscoveryTourApiSourcesTests {
    private final ObjectMapper json = new ObjectMapper();
    private final TourApiUriBuilder uris = new TourApiUriBuilder(URI.create("https://example.test/KorService2"), "fake", "OnMaru");

    @Test void officialRegistryAndDetailIdentityAreValidatedWithoutLeakingUris() throws Exception {
        var code = new TourApiSourceRecord("lclsSystmCode2", json.readTree("""
                {"lclsSystm3Cd":"HS010100"}
                """));
        var detail = new TourApiSourceRecord("detailCommon2", json.readTree("""
                {"contentid":"1","overview":"실제 장소 상세"}
                """));
        var source = new SelectedDiscoveryTourApiSources((operation, uri) -> {
            if (operation.equals("lclsSystmCode2")) return TourApiParseResult.success(
                    new TourApiPage(operation, 1, 1000, 1, true, List.of(code)));
            assertThat(uri.getRawQuery()).contains("contentId=1");
            return TourApiParseResult.success(new TourApiPage(operation, 1, 1, 1, true, List.of(detail)));
        }, uris);
        assertThat(source.officialCodes()).containsExactly("HS010100");
        assertThat(source.fetch(new SourceRecord("kto-tourapi-korean", "areaBasedList2", Map.of("contentid", "1"))))
                .containsEntry("overview", "실제 장소 상세");
    }

    @Test void detailIdentityMismatchFailsTheRun() throws Exception {
        var detail = new TourApiSourceRecord("detailCommon2", json.readTree("""
                {"contentid":"other","overview":"상세"}
                """));
        var source = new SelectedDiscoveryTourApiSources((operation, uri) -> TourApiParseResult.success(
                new TourApiPage(operation, 1, 1, 1, true, List.of(detail))), uris);
        assertThatThrownBy(() -> source.fetch(new SourceRecord("kto-tourapi-korean", "areaBasedList2", Map.of("contentid", "1"))))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("identity mismatch");
    }
}
