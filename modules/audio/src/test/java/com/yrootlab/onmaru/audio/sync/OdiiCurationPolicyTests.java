package com.yrootlab.onmaru.audio.sync;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OdiiCurationPolicyTests {

    private final OdiiCurationPolicy policy = new OdiiCurationPolicy();

    @Test
    void includesTraditionalCultureStory() {
        assertThat(policy.decide(story("한옥마을", "전통문화 해설"), "한옥").status())
                .isEqualTo(OdiiCurationDecision.Status.INCLUDED);
    }

    @Test
    void excludesSportsAndLeisureStory() {
        assertThat(policy.decide(story("스키장 체험", "겨울 스포츠"), "한옥").reason())
                .isEqualTo("EXCLUDED_SPORTS_OR_LEISURE");
    }

    @Test
    void includesTraditionalAccommodationButExcludesGenericAccommodation() {
        assertThat(policy.decide(story("전주 한옥 숙박", "전통 한옥에서 머무는 문화 체험"), "한옥").status())
                .isEqualTo(OdiiCurationDecision.Status.INCLUDED);
        assertThat(policy.decide(story("도심 호텔 숙박", "편안한 객실과 부대시설 안내"), "한옥").reason())
                .isEqualTo("EXCLUDED_GENERAL_COMMERCIAL_CONTENT");
    }

    @Test
    void requiresCulturalContextForFestivalKeyword() {
        assertThat(policy.decide(story("전주 축제", "지역 역사와 문화 해설"), "축제").status())
                .isEqualTo(OdiiCurationDecision.Status.INCLUDED);
        assertThat(policy.decide(story("봄 축제", "즐거운 행사"), "축제").reason())
                .isEqualTo("MISSING_CULTURAL_CONTEXT");
    }

    @Test
    void rejectsMissingStoryId() {
        assertThat(policy.decide(storyWithId("stid", null, "전통문화", "전통문화"), "한옥").reason())
                .isEqualTo("MISSING_STORY_ID");
    }

    private static OdiiSourceStory story(String title, String script) {
        return storyWithId("stid", "stlid", title, script);
    }

    private static OdiiSourceStory storyWithId(String stid, String stlid, String title, String script) {
        return new OdiiSourceStory("tid", "tlid", stid, stlid, title, title,
                script, "https://audio.example/story.mp3", null, "01:00", "127", "37", "ko", null, null);
    }
}
