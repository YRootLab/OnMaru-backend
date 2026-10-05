package com.yrootlab.onmaru.audio.query;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class OdiiStoryThemeTests {

    @Test
    void stableCodeAndLegacyKoreanRequestSelectTheSameTheme() {
        assertThat(OdiiStoryTheme.fromRequest("HANOK_HERITAGE"))
                .contains(OdiiStoryTheme.HANOK_HERITAGE);
        assertThat(OdiiStoryTheme.fromRequest("한옥"))
                .contains(OdiiStoryTheme.HANOK_HERITAGE);
        assertThat(OdiiStoryTheme.fromRequest("한옥/고택"))
                .contains(OdiiStoryTheme.HANOK_HERITAGE);
        assertThat(OdiiStoryTheme.fromRequest("시장"))
                .contains(OdiiStoryTheme.TRADITIONAL_MARKET);
        assertThat(OdiiStoryTheme.fromRequest("마을"))
                .contains(OdiiStoryTheme.VILLAGE_STREETS);
        assertThat(OdiiStoryTheme.fromRequest("궁"))
                .contains(OdiiStoryTheme.PALACE_HISTORY);
        assertThat(OdiiStoryTheme.fromRequest("소리"))
                .contains(OdiiStoryTheme.SOUND_CULTURE);
        assertThat(OdiiStoryTheme.fromRequest("길"))
                .contains(OdiiStoryTheme.NATURE_TRAILS);
    }

    @Test
    void oneStoryMayBelongToSeveralThemesWithoutMatchingBroadSingleSyllables() {
        var tags = List.of("전주 한옥마을", "골목 여행");
        assertThat(OdiiStoryTheme.HANOK_HERITAGE.matches("전주 한옥마을", "이야기", tags, "오디오 관광"))
                .isTrue();
        assertThat(OdiiStoryTheme.VILLAGE_STREETS.matches("전주 한옥마을", "이야기", tags, "오디오 관광"))
                .isTrue();
        assertThat(OdiiStoryTheme.PALACE_HISTORY.matches("강릉 교리단길", "여행", List.of("궁금한 이야기"), "오디오 관광"))
                .isFalse();
        assertThat(OdiiStoryTheme.NATURE_TRAILS.matches("강릉 교리단길", "여행", List.of(), "오디오 관광"))
                .isFalse();
    }

    @Test
    void primaryCategoryOnlyReplacesGenericSourceCategory() {
        assertThat(OdiiStoryTheme.primaryCategory("전주 한옥마을", "이야기", List.of(), "오디오 관광"))
                .isEqualTo("한옥/고택");
        assertThat(OdiiStoryTheme.primaryCategory("전주 한옥마을", "이야기", List.of(), "운영자 분류"))
                .isEqualTo("운영자 분류");
    }
}
