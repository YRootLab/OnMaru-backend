package com.yrootlab.onmaru.audio.sync;

import java.util.Locale;
import java.util.Set;

public final class OdiiCurationPolicy {

    private static final Set<String> EXCLUDED_ACTIVITY_TERMS = Set.of(
            "레저", "스포츠", "골프", "수상레저", "액티비티"
    );
    private static final Set<String> EXCLUDED_GENERAL_TERMS = Set.of(
            "쇼핑", "숙박", "호텔", "리조트"
    );
    private static final Set<String> CULTURAL_TERMS = Set.of(
            "한옥", "고택", "전통", "궁궐", "성곽", "문화유산", "역사", "문화", "유적", "해설", "전통시장"
    );

    public OdiiCurationDecision decide(OdiiSourceStory story, String keyword) {
        if (story == null) {
            return OdiiCurationDecision.excluded("MISSING_SOURCE_STORY");
        }
        String searchable = normalize(String.join(" ",
                value(story.title()), value(story.audioTitle()), value(story.script())));
        if (story.stlid() == null || story.stlid().isBlank()) {
            return OdiiCurationDecision.excluded("MISSING_STORY_ID");
        }
        if (EXCLUDED_ACTIVITY_TERMS.stream().anyMatch(searchable::contains)) {
            return OdiiCurationDecision.excluded("EXCLUDED_SPORTS_OR_LEISURE");
        }
        boolean hasCulturalContext = CULTURAL_TERMS.stream().anyMatch(searchable::contains);
        if (EXCLUDED_GENERAL_TERMS.stream().anyMatch(searchable::contains) && !hasCulturalContext) {
            return OdiiCurationDecision.excluded("EXCLUDED_GENERAL_COMMERCIAL_CONTENT");
        }
        if (Set.of("축제", "자연", "공원").contains(normalize(keyword))
                && !hasCulturalContext) {
            return OdiiCurationDecision.excluded("MISSING_CULTURAL_CONTEXT");
        }
        return OdiiCurationDecision.included();
    }

    public OdiiCurationDecision decide(OdiiSourceStory story) {
        return decide(story, null);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
    }

    private static String value(String value) {
        return value == null ? "" : value;
    }
}
