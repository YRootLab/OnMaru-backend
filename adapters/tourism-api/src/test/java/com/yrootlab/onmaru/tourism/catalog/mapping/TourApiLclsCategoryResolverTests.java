package com.yrootlab.onmaru.tourism.catalog.mapping;

import com.yrootlab.onmaru.catalog.application.qualification.CanonicalCategory;
import com.yrootlab.onmaru.catalog.application.qualification.SourceRecord;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TourApiLclsCategoryResolverTests {

    @Test
    void resolvesCategoriesFromOfficialClassificationNamesInsteadOfPlaceTitles() {
        var resolver = TourApiLclsCategoryResolver.fromOfficialCodes(List.of(
                code("VE", "역사관광", "VE01", "역사유적", "VE010100", "고궁"),
                code("VE", "문화관광", "VE10", "문화시설", "VE100100", "서점"),
                code("SH", "쇼핑", "SH01", "시장", "SH010100", "전통시장"),
                code("FD", "음식", "FD01", "한식", "FD010100", "관광식당"),
                code("EX", "체험관광", "EX03", "농.산.어촌 체험", "EX030100", "체험마을")
        ));

        assertThat(resolver.resolve(place("VE010100", "평범한 이름")))
                .contains(CanonicalCategory.HISTORIC_SITE);
        assertThat(resolver.resolve(place("VE100100", "한옥 서점"))).isEmpty();
        assertThat(resolver.resolve(place("SH010100", "중앙장")))
                .contains(CanonicalCategory.TRADITIONAL_MARKET);
        assertThat(resolver.resolve(place("FD010100", "고유명 음식점")))
                .contains(CanonicalCategory.TRADITIONAL_FOOD);
        assertThat(resolver.resolve(place("EX030100", "산골 마을")))
                .contains(CanonicalCategory.LOCAL_SCENE);
    }

    private SourceRecord code(String c1, String n1, String c2, String n2, String c3, String n3) {
        return new SourceRecord("kto-tourapi-korean", "lclsSystmCode2", Map.of(
                "lclsSystm1Cd", c1, "lclsSystm1Nm", n1,
                "lclsSystm2Cd", c2, "lclsSystm2Nm", n2,
                "lclsSystm3Cd", c3, "lclsSystm3Nm", n3));
    }

    private SourceRecord place(String code, String title) {
        return new SourceRecord("kto-tourapi-korean", "areaBasedList2", Map.of(
                "contentid", "1", "title", title, "lclsSystm3", code));
    }
}
