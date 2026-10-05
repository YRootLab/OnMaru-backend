package com.yrootlab.onmaru.audio.query;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/** Stable Sorimaru theme codes. A story may match more than one theme. */
public enum OdiiStoryTheme {
    HANOK_HERITAGE("한옥/고택", List.of("한옥", "고택", "종택", "전통가옥", "양반가옥", "기와집", "초가집"),
            List.of("한옥", "한옥/고택", "한옥과 고택")),
    TRADITIONAL_MARKET("전통시장/장터", List.of("전통시장", "재래시장", "시장", "장터", "오일장", "5일장", "정기시장"),
            List.of("시장", "전통시장/장터", "전통 시장")),
    VILLAGE_STREETS("마을/골목길", List.of("마을", "골목", "벽화마을", "옛거리", "옛길", "민속촌"),
            List.of("마을", "마을/골목길", "마을과 골목")),
    PALACE_HISTORY("궁궐/역사", List.of("궁궐", "왕궁", "경복궁", "창덕궁", "창경궁", "덕수궁",
            "경희궁", "종묘", "왕릉", "유적", "역사", "문화유산", "문화재", "고분", "성곽", "읍성", "서원", "사적지"),
            List.of("궁", "궁궐/역사", "궁궐과 역사")),
    SOUND_CULTURE("소리/문화", List.of("소리", "국악", "판소리", "풍물", "민요", "음악", "공연", "전통문화",
            "전통예술", "탈춤", "민속놀이", "공예"),
            List.of("소리", "소리/문화", "소리와 문화")),
    NATURE_TRAILS("자연/둘레길", List.of("숲", "둘레길", "산책로", "생태", "자연", "계곡", "해변", "바다",
            "수목원", "습지", "폭포", "호수", "탐방로", "해안길", "국립공원"),
            List.of("길", "자연/둘레길", "자연과 숲길"));

    private static final Pattern GENERIC_STORY_TITLE = Pattern.compile(
            "^([0-9]+[. ]*)?(이야기|소개|해설|오디오)([ 0-9]+)?$", Pattern.CASE_INSENSITIVE);

    private final String displayCategory;
    private final List<String> terms;
    private final List<String> legacyRequests;

    OdiiStoryTheme(String displayCategory, List<String> terms, List<String> legacyRequests) {
        this.displayCategory = displayCategory;
        this.terms = terms;
        this.legacyRequests = legacyRequests;
    }

    public String displayCategory() {
        return displayCategory;
    }

    public List<String> terms() {
        return terms;
    }

    public static Optional<OdiiStoryTheme> fromRequest(String category) {
        if (category == null || category.isBlank()) return Optional.empty();
        String normalized = category.trim();
        return Arrays.stream(values())
                .filter(theme -> theme.name().equalsIgnoreCase(normalized)
                        || theme.legacyRequests.stream().anyMatch(normalized::equals))
                .findFirst();
    }

    public boolean matches(String spotTitle, String storyTitle, List<String> contentTags, String sourceCategory) {
        if (displayCategory.equals(sourceCategory)) return true;
        return matchesText(storyTitle)
                || (contentTags != null && contentTags.stream().anyMatch(this::matchesText))
                || (isGenericStoryTitle(storyTitle) && matchesText(spotTitle));
    }

    private boolean matchesText(String value) {
        if (value == null || value.isBlank()) return false;
        String normalized = value.toLowerCase(Locale.ROOT);
        return terms.stream().anyMatch(normalized::contains);
    }

    private static boolean isGenericStoryTitle(String title) {
        return title == null || title.isBlank() || GENERIC_STORY_TITLE.matcher(title.trim()).matches();
    }

    public boolean matches(OdiiStoryProjection story) {
        return matches(story.title(), story.audioTitle(), story.contentTags(), story.category());
    }

    public static String primaryCategory(
            String spotTitle, String storyTitle, List<String> contentTags, String sourceCategory) {
        if (sourceCategory != null && !sourceCategory.equals("오디오 관광")) return sourceCategory;
        return Arrays.stream(values())
                .filter(theme -> theme.matches(spotTitle, storyTitle, contentTags, sourceCategory))
                .map(OdiiStoryTheme::displayCategory)
                .findFirst()
                .orElse(sourceCategory);
    }
}
