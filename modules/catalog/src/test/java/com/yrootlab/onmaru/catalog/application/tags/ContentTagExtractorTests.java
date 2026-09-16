package com.yrootlab.onmaru.catalog.application.tags;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ContentTagExtractorTests {

    private final ContentTagExtractor extractor = ContentTagExtractor.defaultExtractor();

    @Test
    void ranksDomainPhrasesFromTourismDetailText() {
        var source = ContentTagSource.of(
                "전주 한옥마을",
                "한옥",
                "전통 한옥 골목을 따라 공예 체험과 음식 문화를 함께 즐길 수 있습니다. "
                        + "야간 산책 코스와 사진 명소로도 알려져 있습니다.",
                List.of("한옥 골목", "공예 체험", "야간 산책"));

        var tags = extractor.extract(source, 7);

        assertThat(tags).containsSubsequence("한옥 골목", "공예 체험", "야간 산책");
        assertThat(tags).hasSizeLessThanOrEqualTo(7);
    }

    @Test
    void explainsRankedTagsWithScoreSourceAndAlgorithmVersion() {
        var source = ContentTagSource.of(
                "전주 한옥마을",
                "한옥",
                "전통 한옥 골목과 공예 체험을 소개합니다.",
                List.of("한옥 골목", "공예 체험"));

        var tags = extractor.extractRanked(source, 7);

        assertThat(tags).isNotEmpty();
        assertThat(tags.getFirst().label()).isEqualTo("한옥 골목");
        assertThat(tags.getFirst().score()).isPositive();
        assertThat(tags.getFirst().source()).isEqualTo(ContentTagSourceType.HIGHLIGHT);
        assertThat(tags.getFirst().algorithmVersion()).isEqualTo("content-tags-v2");
    }

    @Test
    void removesGenericProviderWordsAndLimitsResults() {
        var source = ContentTagSource.of(
                "관광지 상세 정보",
                "관광지",
                "관광지 정보 안내 상세 설명입니다. 궁궐 역사 문화 왕실 건축 정원 산책 야경 사진 명소 "
                        + "전통 공연 체험 전시 해설 골목 시장 음식 여행 코스 이야기를 제공합니다.",
                List.of());

        var tags = extractor.extract(source, 7);

        assertThat(tags).hasSize(7);
        assertThat(tags).doesNotContain("관광지", "정보", "상세", "설명", "제공");
        assertThat(tags).contains("궁궐", "사진 명소");
    }

    @Test
    void returnsEmptyTagsWhenTextHasNoUsefulCandidates() {
        var source = ContentTagSource.of("  ", null, "그리고 또는 입니다 합니다", List.of());

        assertThat(extractor.extract(source, 7)).isEmpty();
    }

    @Test
    void loadsDomainPhrasesFromResourceLexicon() {
        var source = ContentTagSource.of(
                "비밀의 정원",
                "정원",
                "고즈넉한 전통 정원과 연못 산책을 함께 즐길 수 있습니다.",
                List.of());

        assertThat(extractor.extract(source, 7)).contains("전통 정원");
    }
}
