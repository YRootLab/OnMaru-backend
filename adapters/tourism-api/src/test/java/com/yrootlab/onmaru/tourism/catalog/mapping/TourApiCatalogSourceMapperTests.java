package com.yrootlab.onmaru.tourism.catalog.mapping;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yrootlab.onmaru.catalog.application.qualification.CanonicalCategory;
import com.yrootlab.onmaru.catalog.application.qualification.SourceQualificationPolicy;
import com.yrootlab.onmaru.tourism.catalog.client.TourApiSourceRecord;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TourApiCatalogSourceMapperTests {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final TourApiCatalogSourceMapper mapper = new TourApiCatalogSourceMapper();
    private final SourceQualificationPolicy policy = SourceQualificationPolicy.withDefaultAllowlist();

    @Test
    void mapsTourApiItemFieldsIntoQualificationSourceRecord() {
        TourApiSourceRecord item = new TourApiSourceRecord("areaBasedList2", objectMapper.createObjectNode()
                .put("contentid", "2944595")
                .put("contenttypeid", "39")
                .put("title", "한옥 카페")
                .put("cat1", "A05")
                .put("cat2", "A0502")
                .put("cat3", "A05020900")
                .put("mapx", "127.148000")
                .put("mapy", "35.815000")
                .put("serviceKey", "MUST_NOT_SURFACE"));

        var result = policy.qualify(mapper.toSourceRecord(item));

        assertThat(result.candidate()).isPresent();
        assertThat(result.candidate().orElseThrow().category()).isEqualTo(CanonicalCategory.HANOK_CAFE);
    }

    @Test
    void quarantineFromMappedTourApiItemDoesNotExposeSecretFields() {
        TourApiSourceRecord item = new TourApiSourceRecord("areaBasedList2", objectMapper.createObjectNode()
                .put("contentid", "900001")
                .put("contenttypeid", "12")
                .put("title", "비허용 관광지")
                .put("cat1", "A01")
                .put("cat2", "A0101")
                .put("cat3", "A01010100")
                .put("mapx", "126.9")
                .put("mapy", "37.5")
                .put("token", "MUST_NOT_SURFACE"));

        var result = policy.qualify(mapper.toSourceRecord(item));

        assertThat(result.quarantine()).isPresent();
        assertThat(result.quarantine().orElseThrow().redactedPayload().toString())
                .doesNotContain("MUST_NOT_SURFACE")
                .contains("<REDACTED>");
    }
}
