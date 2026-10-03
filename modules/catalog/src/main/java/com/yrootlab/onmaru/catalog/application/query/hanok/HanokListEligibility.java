package com.yrootlab.onmaru.catalog.application.query.hanok;

import java.util.List;

/** Shared eligibility for the published hanok snapshot and its compatible detail route. */
public final class HanokListEligibility {

    public static final String KEYWORD = "한옥";
    public static final List<String> CATEGORIES = List.of(
            "HANOK", "HANOK_STAY", "HANOK_CAFE", "HANOK_EXPERIENCE");

    private HanokListEligibility() {
    }

    public static boolean matches(String category, String name, String overview) {
        return isHanokCategory(category) || containsKeyword(name) || containsKeyword(overview);
    }

    public static boolean isHanokCategory(String category) {
        return KEYWORD.equals(category) || category != null && CATEGORIES.contains(category);
    }

    private static boolean containsKeyword(String value) {
        return value != null && value.contains(KEYWORD);
    }
}
