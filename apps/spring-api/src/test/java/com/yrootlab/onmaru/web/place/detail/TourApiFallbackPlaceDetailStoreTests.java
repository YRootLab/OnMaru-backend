package com.yrootlab.onmaru.web.place.detail;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yrootlab.onmaru.catalog.application.query.detail.CoordinatesProjection;
import com.yrootlab.onmaru.catalog.application.query.detail.PlaceProjection;
import com.yrootlab.onmaru.catalog.application.query.detail.PlaceDetailQueryService;
import com.yrootlab.onmaru.catalog.application.query.detail.RegionProjection;
import com.yrootlab.onmaru.persistence.catalog.TourApiPlaceReference;
import com.yrootlab.onmaru.tourism.catalog.client.TourApiPage;
import com.yrootlab.onmaru.tourism.catalog.client.TourApiParseResult;
import com.yrootlab.onmaru.tourism.catalog.client.TourApiSourceRecord;
import com.yrootlab.onmaru.tourism.catalog.client.TourApiUriBuilder;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class TourApiFallbackPlaceDetailStoreTests {
    private final ObjectMapper json = new ObjectMapper();
    private final PlaceProjection base = PlaceProjection.publicPlace(
            "p-tourapi-126508", "기본 장소", "HISTORIC_SITE",
            new RegionProjection("kr-11", "서울"), "서울 종로구",
            new CoordinatesProjection(37.5, 127.0), List.of(), "DB 기본 설명", List.of(), null);
    private final TourApiUriBuilder uris = new TourApiUriBuilder(
            URI.create("https://example.test"), "test-key", "OnMaruTest");

    @Test
    void enrichesDatabaseBaseWhenTourApiSucceeds() {
        var store = new TourApiFallbackPlaceDetailStore(
                id -> Optional.of(base),
                id -> Optional.of(new TourApiPlaceReference(id, "126508", "12")),
                id -> false,
                (operation, uri) -> switch (operation) {
                    case "detailCommon2" -> page(operation, "{\"overview\":\"상세 설명\",\"firstimage\":\"https://image.test/a.jpg\"}");
                    case "detailIntro2" -> page(operation, "{\"usetime\":\"09:00~18:00\",\"parking\":\"가능\"}");
                    default -> page(operation, "{\"infoname\":\"안내\",\"infotext\":\"해설 제공\"}");
                }, uris);

        var result = store.findByPlaceId(base.placeId()).orElseThrow();

        assertThat(result.description()).isEqualTo("상세 설명");
        assertThat(result.images()).extracting("url").containsExactly("https://image.test/a.jpg");
        assertThat(result.highlights()).contains("09:00~18:00", "가능", "해설 제공");
    }

    @Test
    void returnsDatabaseBaseWhenTourApiFails() {
        var store = new TourApiFallbackPlaceDetailStore(
                id -> Optional.of(base),
                id -> Optional.of(new TourApiPlaceReference(id, "126508", "12")),
                id -> false,
                (operation, uri) -> { throw new java.io.IOException("provider unavailable"); }, uris);

        assertThat(store.findByPlaceId(base.placeId())).contains(base);
    }

    @Test
    void missingDatabasePlaceDoesNotCallTourApiAndRemainsNotFound() {
        var store = new TourApiFallbackPlaceDetailStore(
                id -> Optional.empty(), id -> { throw new AssertionError("reference lookup must not run"); }, id -> false,
                (operation, uri) -> { throw new AssertionError("TourAPI must not run"); }, uris);

        assertThat(store.findByPlaceId("missing")).isEmpty();
    }

    @Test
    void storedHanokDetailWinsWithoutCallingTourApi() {
        var store = new TourApiFallbackPlaceDetailStore(
                id -> Optional.of(base),
                id -> { throw new AssertionError("reference lookup must not run"); },
                id -> true,
                (operation, uri) -> { throw new AssertionError("TourAPI must not run"); }, uris);

        assertThat(store.findByPlaceId(base.placeId())).contains(base);
    }

    @Test
    void hanokDetailKeepsSnapshotEligibilityWhenEnrichmentReplacesItsOverview() {
        var eligible = PlaceProjection.publicPlace(
                base.placeId(), base.name(), base.category(), base.region(), base.address(),
                base.coordinates(), base.images(), "전통 한옥을 둘러보는 마을", List.of(), null);
        var store = new TourApiFallbackPlaceDetailStore(
                id -> Optional.of(eligible),
                id -> Optional.of(new TourApiPlaceReference(id, "126508", "12")),
                id -> false,
                (operation, uri) -> page(operation, "{\"overview\":\"새로운 역사 문화 안내\"}"), uris);
        var service = new PlaceDetailQueryService(store, (memberId, placeId) -> false);

        assertThat(service.findHanok(base.placeId(), Optional.empty()))
                .hasValueSatisfying(detail -> {
                    assertThat(detail.category().name()).isEqualTo("HISTORIC_SITE");
                    assertThat(detail.description()).isEqualTo("새로운 역사 문화 안내");
                });
    }

    @Test
    void enrichmentCannotMakeAnUnrelatedSnapshotPlaceEligibleForHanokDetail() {
        var store = new TourApiFallbackPlaceDetailStore(
                id -> Optional.of(base),
                id -> Optional.of(new TourApiPlaceReference(id, "126508", "12")),
                id -> false,
                (operation, uri) -> page(operation, "{\"overview\":\"주변 한옥 관광 안내\"}"), uris);
        var service = new PlaceDetailQueryService(store, (memberId, placeId) -> false);

        assertThat(service.findHanok(base.placeId(), Optional.empty())).isEmpty();
        assertThat(service.findCanonicalPlace(base.placeId(), Optional.empty()))
                .hasValueSatisfying(detail ->
                        assertThat(detail.description()).isEqualTo("주변 한옥 관광 안내"));
    }

    private TourApiParseResult page(String operation, String body) throws Exception {
        return TourApiParseResult.success(new TourApiPage(operation, 1, 1, 1, true,
                List.of(new TourApiSourceRecord(operation, json.readTree(body)))));
    }
}
