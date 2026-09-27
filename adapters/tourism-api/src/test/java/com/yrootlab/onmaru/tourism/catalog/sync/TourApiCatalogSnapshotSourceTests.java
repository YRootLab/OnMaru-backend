package com.yrootlab.onmaru.tourism.catalog.sync;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yrootlab.onmaru.tourism.catalog.client.TourApiPage;
import com.yrootlab.onmaru.tourism.catalog.client.TourApiParseResult;
import com.yrootlab.onmaru.tourism.catalog.client.TourApiSourceRecord;
import com.yrootlab.onmaru.tourism.catalog.client.TourApiUriBuilder;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TourApiCatalogSnapshotSourceTests {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void streamsEveryAreaPageAndEnrichesV44ClassificationFromOfficialCodes() {
        var requested = new ArrayList<String>();
        TourApiPageFetcher fetcher = (operation, uri) -> {
            requested.add(uri.toString());
            if (operation.equals("lclsSystmCode2")) {
                return page(operation, 1, 1, 1, code());
            }
            int pageNo = uri.toString().contains("pageNo=2") ? 2 : 1;
            return page(operation, pageNo, 1, 2, place(Integer.toString(pageNo)));
        };
        var source = new TourApiCatalogSnapshotSource(fetcher, new TourApiUriBuilder(
                URI.create("https://apis.data.go.kr/B551011/KorService2"), "key", "OnMaru"), 1);
        var streamed = new ArrayList<com.yrootlab.onmaru.catalog.application.qualification.SourceRecord>();

        var result = source.streamFullSnapshot(streamed::addAll);

        assertThat(result.rawCount()).isEqualTo(2);
        assertThat(streamed).hasSize(2).allSatisfy(row ->
                assertThat(row.field("canonicalcategory")).isEqualTo("HISTORIC_SITE"));
        assertThat(requested).anyMatch(uri -> uri.contains("lclsSystmCode2") && uri.contains("lclsSystmListYn=Y"));
        assertThat(requested).filteredOn(uri -> uri.contains("areaBasedList2")).hasSize(2);
    }

    @Test
    void stopsAtTotalCountWhenProviderReportsTheLastPageRowCountAsNumOfRows() {
        var requested = new ArrayList<String>();
        TourApiPageFetcher fetcher = (operation, uri) -> {
            requested.add(uri.toString());
            if (operation.equals("lclsSystmCode2")) return page(operation, 1, 1, 1, code());
            if (uri.toString().contains("pageNo=1")) {
                return TourApiParseResult.success(new TourApiPage(operation, 1, 2, 3,
                        false, List.of(place("1"), place("2"))));
            }
            return TourApiParseResult.success(new TourApiPage(operation, 2, 1, 3,
                    false, List.of(place("3"))));
        };
        var source = new TourApiCatalogSnapshotSource(fetcher, new TourApiUriBuilder(
                URI.create("https://apis.data.go.kr/B551011/KorService2"), "key", "OnMaru"), 1000);
        var streamed = new ArrayList<com.yrootlab.onmaru.catalog.application.qualification.SourceRecord>();

        var result = source.streamFullSnapshot(streamed::addAll);

        assertThat(result.rawCount()).isEqualTo(3);
        assertThat(requested).filteredOn(uri -> uri.contains("areaBasedList2")).hasSize(2);
    }

    private TourApiParseResult page(String operation, int pageNo, int size, int total, TourApiSourceRecord item) {
        return TourApiParseResult.success(new TourApiPage(operation, pageNo, size, total,
                pageNo * size >= total, List.of(item)));
    }

    private TourApiSourceRecord code() {
        return new TourApiSourceRecord("lclsSystmCode2", objectMapper.createObjectNode()
                .put("lclsSystm1Cd", "VE").put("lclsSystm1Nm", "역사관광")
                .put("lclsSystm2Cd", "VE01").put("lclsSystm2Nm", "역사유적")
                .put("lclsSystm3Cd", "VE010100").put("lclsSystm3Nm", "고궁"));
    }

    private TourApiSourceRecord place(String id) {
        return new TourApiSourceRecord("areaBasedList2", objectMapper.createObjectNode()
                .put("contentid", id).put("title", "장소 " + id)
                .put("lclsSystm1", "VE").put("lclsSystm2", "VE01").put("lclsSystm3", "VE010100")
                .put("mapx", "126.98").put("mapy", "37.58"));
    }
}
