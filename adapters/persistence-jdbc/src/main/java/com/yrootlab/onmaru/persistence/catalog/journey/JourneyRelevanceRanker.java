package com.yrootlab.onmaru.persistence.catalog.journey;

import com.yrootlab.onmaru.catalog.application.query.journey.ApprovedPlaceLookupItem;

import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Ranks nationwide approved places against the visitor's question. The region is a bonus, never a filter:
 * a place outside the requested region is still eligible, it just ranks lower than an equally relevant one inside it.
 */
final class JourneyRelevanceRanker {

    static final int MAX_TOKENS = 6;

    private static final Pattern TOKEN_SPLIT = Pattern.compile("[^\\p{L}\\p{N}]+");
    private static final Set<String> STOPWORDS = Set.of(
            "추천", "추천해", "추천해주세요", "추천해줘", "주세요", "해주세요", "알려", "알려주세요", "해줘",
            "있는", "곳", "좋은", "싶어요", "싶어", "가고", "어디", "근처", "여행", "코스", "코스를", "코스로",
            "한", "좀", "그리고", "에서", "으로", "하고", "하는");

    private static final int NAME_HIT = 3;
    private static final int CATEGORY_HIT = 2;
    private static final int ADDRESS_HIT = 1;
    private static final int THEME_CATEGORY_HIT = 3;
    private static final int REGION_HIT = 2;

    private JourneyRelevanceRanker() {
    }

    /** Meaningful query terms, lower-cased, de-duplicated, capped. Stopwords and one-character noise are dropped. */
    static List<String> tokens(String query) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        var seen = new LinkedHashSet<String>();
        for (String raw : TOKEN_SPLIT.split(query.toLowerCase(Locale.ROOT))) {
            if (raw.codePointCount(0, raw.length()) < 2 || STOPWORDS.contains(raw)) {
                continue;
            }
            seen.add(raw);
            if (seen.size() == MAX_TOKENS) {
                break;
            }
        }
        return List.copyOf(seen);
    }

    static List<ApprovedPlaceLookupItem> rank(
            List<String> tokens, String regionCode, List<ApprovedPlaceLookupItem> pool, int limit) {
        return pool.stream()
                .map(place -> new Scored(place, score(tokens, regionCode, place)))
                .sorted(Comparator.comparingInt(Scored::score).reversed()
                        .thenComparing(entry -> entry.place().name(), Comparator.nullsLast(Comparator.<String>naturalOrder()))
                        .thenComparing(entry -> entry.place().placeId()))
                .limit(limit)
                .map(Scored::place)
                .toList();
    }

    static int score(List<String> tokens, String regionCode, ApprovedPlaceLookupItem place) {
        int score = 0;
        String name = lower(place.name());
        String category = lower(place.category());
        String address = lower(place.address());
        for (String token : tokens) {
            if (name.contains(token)) {
                score += NAME_HIT;
            }
            if (category.contains(token)) {
                score += CATEGORY_HIT;
            }
            if (address.contains(token)) {
                score += ADDRESS_HIT;
            }
            if (isHanokTheme(token) && category.startsWith("hanok")) {
                score += THEME_CATEGORY_HIT;
            }
        }
        if (regionCode != null && place.regionCode() != null && place.regionCode().startsWith(regionCode)) {
            score += REGION_HIT;
        }
        return score;
    }

    static boolean isHanokTheme(String token) {
        return token.contains("한옥") || token.contains("고택");
    }

    private static String lower(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }

    private record Scored(ApprovedPlaceLookupItem place, int score) {
    }
}
