package com.yrootlab.onmaru.catalog.application.tags;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ContentTagPipelineTests {

    private final ContentTagPipeline pipeline = ContentTagPipeline.defaultPipeline();

    @Test
    void filtersGenericTagsAndReportsQualityWithoutManualOverride() {
        var result = pipeline.generate(ContentTagSource.of(
                "관광 정보 안내",
                "관광",
                "관광 정보 안내 소개 코스 여행 궁궐 정원 산책 사진 명소",
                List.of()), 7);

        assertThat(result.publicLabels()).doesNotContain(
                "관광", "정보", "안내", "소개", "코스", "여행", "소개 코스", "코스 여행");
        assertThat(result.publicLabels()).contains("궁궐", "정원 산책");
        assertThat(result.qualityReport().removedGenericCount()).isGreaterThan(0);
        assertThat(result.qualityReport().emptyResult()).isFalse();
    }

    @Test
    void appliesHideThenPinAsExceptionSafetyValve() {
        var result = pipeline.generate(ContentTagSource.of(
                        "전주 한옥마을",
                        "한옥",
                        "한옥 골목과 공예 체험을 소개합니다.",
                        List.of("한옥 골목")),
                3,
                List.of(
                        ContentTagOverride.hide("한옥 골목"),
                        ContentTagOverride.pin("전주 한옥마을")));

        assertThat(result.publicLabels()).startsWith("전주 한옥마을");
        assertThat(result.publicLabels()).doesNotContain("한옥 골목");
        assertThat(result.publicLabels()).hasSizeLessThanOrEqualTo(3);
        assertThat(result.qualityReport().hiddenOverrideCount()).isEqualTo(1);
        assertThat(result.qualityReport().pinnedOverrideCount()).isEqualTo(1);
    }

    @Test
    void sourceHashIsStableForEquivalentWhitespace() {
        var left = pipeline.generate(ContentTagSource.of(
                " 전주  한옥마을 ",
                "한옥",
                "골목 산책",
                List.of(" 공예  체험 ")), 7);
        var right = pipeline.generate(ContentTagSource.of(
                "전주 한옥마을",
                "한옥",
                "골목   산책",
                List.of("공예 체험")), 7);

        assertThat(left.sourceHash()).isEqualTo(right.sourceHash());
        assertThat(left.sourceHash()).hasSize(64);
        assertThat(left.algorithmVersion()).isEqualTo("content-tags-v2");
    }

    @Test
    void loadsGenericLabelsFromResourceLexicon() {
        var result = pipeline.generate(ContentTagSource.of(
                "추천 안내",
                "추천",
                "추천 안내 추천 코스 궁궐 정원 산책",
                List.of()), 7);

        assertThat(result.publicLabels()).doesNotContain("추천", "추천 안내");
        assertThat(result.qualityReport().removedGenericCount()).isGreaterThan(0);
    }

    @Test
    void reportsGenericHeavyWhenAutomaticFilterRemovesManyGenericCandidates() {
        var result = pipeline.generate(ContentTagSource.of(
                "추천 소개 이야기",
                "추천",
                "추천 소개 이야기 추천 소개 이야기 추천 소개 이야기 궁궐 정원 산책",
                List.of()), 7);

        assertThat(result.qualityReport().warnings()).contains("GENERIC_HEAVY");
    }
}
