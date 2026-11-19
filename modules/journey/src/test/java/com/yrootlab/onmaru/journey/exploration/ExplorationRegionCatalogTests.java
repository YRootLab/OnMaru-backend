package com.yrootlab.onmaru.journey.exploration;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ExplorationRegionCatalogTests {

    private final ExplorationRegionCatalog catalog = ExplorationRegionCatalog.fromEntries(List.of(
            new ExplorationRegionCatalog.RegionEntry("kr-11", "서울특별시", "SIDO"),
            new ExplorationRegionCatalog.RegionEntry("kr-11-jongno", "종로구", "SIGUNGU", "서울특별시"),
            new ExplorationRegionCatalog.RegionEntry("kr-datalab-11140", "중구", "SIGUNGU", "서울특별시"),
            new ExplorationRegionCatalog.RegionEntry("kr-datalab-26", "부산광역시", "SIDO"),
            new ExplorationRegionCatalog.RegionEntry("kr-datalab-26110", "중구", "SIGUNGU", "부산광역시"),
            new ExplorationRegionCatalog.RegionEntry("kr-datalab-12", "전남광주통합특별시", "SIDO"),
            new ExplorationRegionCatalog.RegionEntry("kr-datalab-41", "경기도", "SIDO"),
            new ExplorationRegionCatalog.RegionEntry("kr-datalab-41610", "광주시", "SIGUNGU", "경기도"),
            new ExplorationRegionCatalog.RegionEntry("kr-datalab-41115", "수원시 팔달구", "SIGUNGU", "경기도"),
            new ExplorationRegionCatalog.RegionEntry("kr-45", "전북특별자치도", "SIDO"),
            new ExplorationRegionCatalog.RegionEntry("kr-45-jeonju", "전주시", "SIGUNGU", "전북특별자치도"),
            new ExplorationRegionCatalog.RegionEntry("kr-datalab-47", "경상북도", "SIDO"),
            new ExplorationRegionCatalog.RegionEntry("kr-datalab-47130", "경주시", "SIGUNGU", "경상북도"),
            new ExplorationRegionCatalog.RegionEntry("kr-datalab-47170", "안동시", "SIGUNGU", "경상북도"),
            new ExplorationRegionCatalog.RegionEntry("kr-datalab-48", "경상남도", "SIDO"),
            new ExplorationRegionCatalog.RegionEntry("kr-datalab-48820", "고성군", "SIGUNGU", "경상남도"),
            new ExplorationRegionCatalog.RegionEntry("kr-datalab-50", "제주특별자치도", "SIDO"),
            new ExplorationRegionCatalog.RegionEntry("kr-datalab-50110", "제주시", "SIGUNGU", "제주특별자치도"),
            new ExplorationRegionCatalog.RegionEntry("kr-datalab-51", "강원특별자치도", "SIDO"),
            new ExplorationRegionCatalog.RegionEntry("kr-datalab-51150", "강릉시", "SIGUNGU", "강원특별자치도"),
            new ExplorationRegionCatalog.RegionEntry("kr-datalab-51820", "고성군", "SIGUNGU", "강원특별자치도"),
            new ExplorationRegionCatalog.RegionEntry("kr-datalab-36", "세종특별자치시", "SIDO"),
            new ExplorationRegionCatalog.RegionEntry("kr-datalab-36110", "세종특별자치시", "SIGUNGU", "세종특별자치시")
    ));

    @Test
    void resolvesAllRegionsBeyondLegacyFive() {
        assertThat(catalog.resolveRegionCode("강릉 한옥 추천해줘")).contains("kr-datalab-51150");
        assertThat(catalog.resolveRegionCode("경주 불국사 산책")).contains("kr-datalab-47130");
        assertThat(catalog.resolveRegionCode("안동에서 가볼 만한 곳")).contains("kr-datalab-47170");
        assertThat(catalog.resolveRegionCode("부산으로 떠나는 여행")).contains("kr-datalab-26");
        assertThat(catalog.resolveRegionCode("수원시 팔달구 화성 행궁")).contains("kr-datalab-41115");
    }

    @Test
    void prefersMoreSpecificSigunguOverContainingSido() {
        assertThat(catalog.resolveRegionCode("서울 종로구 한옥 산책")).contains("kr-11-jongno");
        assertThat(catalog.resolveRegionCode("서울 한옥 산책")).contains("kr-11");
    }

    @Test
    void prefersSidoOnSidoSigunguShortAliasCollisionUnlessParentQualified() {
        assertThat(catalog.resolveRegionCode("제주 한옥 추천")).contains("kr-datalab-50");
        assertThat(catalog.resolveRegionCode("제주시 한옥 추천")).contains("kr-datalab-50110");
        assertThat(catalog.resolveRegionCode("세종 고택 산책")).contains("kr-datalab-36");
        assertThat(catalog.resolveRegionCode("광주 한옥 추천")).contains("kr-datalab-12");
        assertThat(catalog.resolveRegionCode("경기도 광주시 한옥")).contains("kr-datalab-41610");
        assertThat(catalog.resolveRegionCode("경기 광주 한옥")).contains("kr-datalab-41610");
    }

    @Test
    void skipsAmbiguousWardOrCountyUnlessQualifiedByParent() {
        assertThat(catalog.resolveRegionCode("중구 한옥 추천")).isEmpty();
        assertThat(catalog.resolveRegionCode("서울 중구 한옥 추천")).contains("kr-datalab-11140");
        assertThat(catalog.resolveRegionCode("부산 중구 산책")).contains("kr-datalab-26110");
        assertThat(catalog.resolveRegionCode("고성 여행 추천")).isEmpty();
        assertThat(catalog.resolveRegionCode("강원 고성 여행 추천")).contains("kr-datalab-51820");
        assertThat(catalog.resolveRegionCode("경남 고성 여행 추천")).contains("kr-datalab-48820");
    }

    @Test
    void respectsKoreanTokenBoundaries() {
        assertThat(catalog.resolveRegionCode("안동찜닭 말고 맛있는 집")).isEmpty();
        assertThat(catalog.resolveRegionCode("안동에서 볼만한 고택")).contains("kr-datalab-47170");
    }
}
