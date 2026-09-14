package com.yrootlab.onmaru.tourism.catalog.client;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TourApiUriBuilderContractTests {

    private final TourApiUriBuilder builder = new TourApiUriBuilder(
            URI.create("https://apis.data.go.kr/B551011/KorService2"),
            "service key with spaces",
            "OnMaru"
    );

    @Test
    void buildsAreaBasedListUriWithDefaultProviderParameters() {
        URI uri = builder.areaBasedList(1, 20, Map.of(
                "contentTypeId", "12",
                "areaCode", "1",
                "arrange", "A"
        ));

        assertThat(uri.toString())
                .startsWith("https://apis.data.go.kr/B551011/KorService2/areaBasedList2?")
                .contains("MobileOS=ETC")
                .contains("MobileApp=OnMaru")
                .contains("_type=json")
                .contains("serviceKey=service+key+with+spaces")
                .contains("pageNo=1")
                .contains("numOfRows=20")
                .contains("contentTypeId=12")
                .contains("areaCode=1")
                .contains("arrange=A");
    }

    @Test
    void buildsDetailCommonUriWithRequiredFlags() {
        URI uri = builder.detailCommon("126508", "12");

        assertThat(uri.toString())
                .startsWith("https://apis.data.go.kr/B551011/KorService2/detailCommon2?")
                .contains("contentId=126508")
                .contains("contentTypeId=12")
                .contains("defaultYN=Y")
                .contains("firstImageYN=Y")
                .contains("areacodeYN=Y")
                .contains("catcodeYN=Y")
                .contains("addrinfoYN=Y")
                .contains("mapinfoYN=Y")
                .contains("overviewYN=Y");
    }

    @Test
    void buildsAreaCodeUriForQualificationOperation() {
        URI uri = builder.areaCode(1, 10);

        assertThat(uri.toString())
                .startsWith("https://apis.data.go.kr/B551011/KorService2/areaCode2?")
                .contains("pageNo=1")
                .contains("numOfRows=10");
    }

    @Test
    void doesNotDoubleEncodePortalEncodedServiceKey() {
        TourApiUriBuilder encodedKeyBuilder = new TourApiUriBuilder(
                URI.create("https://apis.data.go.kr/B551011/KorService2"),
                "abc%2Bdef%3D",
                "OnMaru"
        );

        URI uri = encodedKeyBuilder.areaCode(1, 10);

        assertThat(uri.toString())
                .contains("serviceKey=abc%2Bdef%3D")
                .doesNotContain("serviceKey=abc%252Bdef%253D");
    }

    @Test
    void encodesSearchKeywordValues() {
        URI uri = builder.searchKeyword("한옥 마을", 2, 10);

        assertThat(uri.toString())
                .startsWith("https://apis.data.go.kr/B551011/KorService2/searchKeyword2?")
                .contains("keyword=%ED%95%9C%EC%98%A5+%EB%A7%88%EC%9D%84")
                .contains("pageNo=2")
                .contains("numOfRows=10");
    }

    @Test
    void rejectsInvalidPageNoBeforeCallingProvider() {
        assertThatThrownBy(() -> builder.areaBasedList(0, 10, Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("pageNo");
    }

    @Test
    void rejectsNullOptionalParamsBeforeCallingProvider() {
        assertThatThrownBy(() -> builder.areaBasedList(1, 10, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("optionalParams");
    }

    @Test
    void redactsServiceKeyFromDiagnosticUri() {
        URI uri = builder.areaBasedList(1, 10, Map.of("areaCode", "1"));

        assertThat(TourApiUriBuilder.redactServiceKey(uri))
                .contains("serviceKey=%3CREDACTED%3E")
                .doesNotContain("service+key+with+spaces");
    }
}
