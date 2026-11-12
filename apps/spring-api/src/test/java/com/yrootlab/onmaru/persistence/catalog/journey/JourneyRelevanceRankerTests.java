package com.yrootlab.onmaru.persistence.catalog.journey;

import com.yrootlab.onmaru.catalog.application.query.journey.ApprovedPlaceLookupItem;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class JourneyRelevanceRankerTests {

    private static ApprovedPlaceLookupItem place(String id, String name, String category, String address, String region) {
        return new ApprovedPlaceLookupItem(id, name, category, address, region, 37.5, 127.0);
    }

    @Test
    void extractsMeaningfulTermsAndDropsStopwords() {
        assertThat(JourneyRelevanceRanker.tokens("조용한 한옥 코스를 추천해 주세요")).containsExactly("조용한", "한옥");
        assertThat(JourneyRelevanceRanker.tokens(null)).isEmpty();
        assertThat(JourneyRelevanceRanker.tokens("  ")).isEmpty();
    }

    @Test
    void regionIsABonusNotAFilter() {
        var busanMatch = place("p-busan", "한옥 마을", "HANOK", "부산 동구", "kr-26-busan");
        var seoulNoMatch = place("p-seoul", "도심 카페", "CAFE", "서울 종로구", "kr-11-jongno");

        var ranked = JourneyRelevanceRanker.rank(List.of("한옥"), "kr-11", List.of(seoulNoMatch, busanMatch), 12);

        assertThat(ranked).extracting(ApprovedPlaceLookupItem::placeId).containsExactly("p-busan", "p-seoul");
    }

    @Test
    void regionBreaksTiesBetweenEquallyRelevantPlaces() {
        var elsewhere = place("p-a", "고요한 한옥", "HANOK", "강원 강릉시", "kr-42-gangneung");
        var inRegion = place("p-b", "고요한 한옥", "HANOK", "서울 종로구", "kr-11-jongno");

        var ranked = JourneyRelevanceRanker.rank(List.of("한옥"), "kr-11", List.of(elsewhere, inRegion), 12);

        assertThat(ranked.get(0).placeId()).isEqualTo("p-b");
    }

    @Test
    void hanokThemeRanksHanokCategoryAboveUnrelatedPlaces() {
        var hanok = place("p-hanok", "고즈넉한 집", "HANOK_STAY", "경북 경주시", "kr-47-gyeongju");
        var unrelated = place("p-cafe", "가나다 카페", "CAFE", "경북 경주시", "kr-47-gyeongju");

        var ranked = JourneyRelevanceRanker.rank(List.of("한옥"), null, List.of(unrelated, hanok), 12);

        assertThat(ranked.get(0).placeId()).isEqualTo("p-hanok");
    }

    @Test
    void withoutUsableTermsTheRegionDecidesOrderOnly() {
        var seoul = place("p-seoul", "가", "CAFE", "서울", "kr-11-jongno");
        var busan = place("p-busan", "나", "CAFE", "부산", "kr-26-busan");

        var ranked = JourneyRelevanceRanker.rank(List.of(), "kr-11", List.of(busan, seoul), 12);

        assertThat(ranked).extracting(ApprovedPlaceLookupItem::placeId).containsExactly("p-seoul", "p-busan");
    }

    @Test
    void keepsLimitAndNeverDuplicatesPlaces() {
        var pool = List.of(
                place("p-1", "한옥 하나", "HANOK", "서울", "kr-11-jongno"),
                place("p-2", "한옥 둘", "HANOK", "서울", "kr-11-jongno"),
                place("p-3", "한옥 셋", "HANOK", "부산", "kr-26-busan"));

        var ranked = JourneyRelevanceRanker.rank(List.of("한옥"), "kr-11", pool, 2);

        assertThat(ranked).hasSize(2);
        assertThat(ranked).extracting(ApprovedPlaceLookupItem::placeId).doesNotHaveDuplicates();
    }
}
