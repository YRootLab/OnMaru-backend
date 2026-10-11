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
import java.util.Map;
import com.yrootlab.onmaru.catalog.application.sourcefetch.SelectedSourceFetch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

    @Test
    void verifiedPlaceKeywordMustReturnItsExactContentId() {
        TourApiPageFetcher fetcher = (operation, uri) -> {
            if (operation.equals("lclsSystmCode2")) {
                var codes = SelectedSourceFetch.CODES.stream().map(code ->
                        new TourApiSourceRecord(operation, objectMapper.createObjectNode()
                                .put("lclsSystm3Cd", code).put("lclsSystm3Nm", "고궁"))).toList();
                var all = new ArrayList<>(codes);
                all.add(new TourApiSourceRecord(operation, objectMapper.createObjectNode()
                        .put("lclsSystm3Cd", "HS030200").put("lclsSystm3Nm", "종교성지")));
                return TourApiParseResult.success(new TourApiPage(operation, 1, 1000, all.size(), true, all));
            }
            var query = java.net.URLDecoder.decode(uri.getRawQuery(), java.nio.charset.StandardCharsets.UTF_8);
            var rows = query.contains("keyword=옥천성당")
                    ? List.of(new TourApiSourceRecord(operation, objectMapper.createObjectNode()
                            .put("contentid", "2717818").put("title", "옥천성당")
                            .put("lclsSystm3", "HS030200").put("mapx", "127.5").put("mapy", "36.3")))
                    : List.<TourApiSourceRecord>of();
            return TourApiParseResult.success(new TourApiPage(operation, 1, 1000, rows.size(), true, rows));
        };
        var source = new TourApiCatalogSnapshotSource(fetcher, new TourApiUriBuilder(
                URI.create("https://apis.data.go.kr/B551011/KorService2"), "key", "OnMaru"), 1000);
        var streamed = new ArrayList<com.yrootlab.onmaru.catalog.application.qualification.SourceRecord>();
        assertThat(source.streamSelectedSnapshot(streamed::addAll, Map.of("2717818", "옥천성당")).rawCount())
                .isEqualTo(1);
        assertThat(streamed.getFirst().field("contentid")).isEqualTo("2717818");
        assertThatThrownBy(() -> source.streamSelectedSnapshot(rows -> { }, Map.of("9999999", "옥천성당")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("omits a verified K-Contents place");
    }

    @Test
    void selectedSnapshotUnionsNewCodesLegacyCategoriesAndKeywordsWithoutFullScan() {
        var requested = new ArrayList<String>();
        TourApiPageFetcher fetcher = (operation, uri) -> {
            requested.add(uri.toString());
            if (operation.equals("lclsSystmCode2")) {
                var codes = SelectedSourceFetch.CODES.stream().map(code ->
                        new TourApiSourceRecord(operation, objectMapper.createObjectNode()
                                .put("lclsSystm3Cd", code).put("lclsSystm3Nm", "고궁"))).toList();
                return TourApiParseResult.success(new TourApiPage(operation, 1, 1000, codes.size(), true, codes));
            }
            List<TourApiSourceRecord> rows = List.of();
            if (uri.toString().contains("lclsSystm3=HS010100") || uri.toString().contains("keyword=%ED%95%9C%EC%98%A5"))
                rows = List.of(new TourApiSourceRecord(operation, objectMapper.createObjectNode()
                        .put("contentid", "1").put("title", "한옥").put("lclsSystm3", "HS010100")
                        .put("mapx", "126.98").put("mapy", "37.58")));
            if (uri.toString().contains("cat3=A02010300"))
                rows = List.of(new TourApiSourceRecord(operation, objectMapper.createObjectNode()
                        .put("contentid", "2").put("title", "고궁")
                        .put("contenttypeid", "12").put("cat1", "A02")
                        .put("cat2", "A0201").put("cat3", "A02010300")
                        .put("mapx", "126.97").put("mapy", "37.57")));
            return TourApiParseResult.success(new TourApiPage(operation, 1, 1000, rows.size(), true, rows));
        };
        var source = new TourApiCatalogSnapshotSource(fetcher, new TourApiUriBuilder(
                URI.create("https://apis.data.go.kr/B551011/KorService2"), "key", "OnMaru"), 1000);
        var streamed = new ArrayList<com.yrootlab.onmaru.catalog.application.qualification.SourceRecord>();

        var result = source.streamSelectedSnapshot(streamed::addAll);

        assertThat(result.rawCount()).isEqualTo(2);
        assertThat(streamed).extracting(row -> row.field("contentid")).containsExactly("1", "2");
        assertThat(requested).noneMatch(uri -> uri.contains("areaBasedList2")
                && !uri.contains("lclsSystm3=") && !uri.contains("cat3="));
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
