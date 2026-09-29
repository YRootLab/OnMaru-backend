package com.yrootlab.onmaru.catalog.application.query.mapinfo;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Versioned, explicit mapping. It intentionally does not classify by title text. */
public final class MapInfoCategoryMapping {
    public static final String VERSION = "map-category-v1";

    private static final Map<MapInfoCategory, List<String>> MAPPING = Map.ofEntries(
            Map.entry(MapInfoCategory.SPOT, List.of("HANOK", "HISTORIC_SITE", "CULTURE_ART", "HANOK_VILLAGE", "GOTAek", "SPOT")),
            Map.entry(MapInfoCategory.EXPERIENCE, List.of("HANOK_EXPERIENCE", "LOCAL_SCENE", "EXPERIENCE")),
            Map.entry(MapInfoCategory.CULTURE, List.of("CULTURE", "CULTURE_ART", "CULTURAL_HERITAGE")),
            Map.entry(MapInfoCategory.FESTIVAL, List.of("FESTIVAL", "EVENT")),
            Map.entry(MapInfoCategory.STAY, List.of("HANOK_STAY", "HANOK_HOTEL", "STAY")),
            Map.entry(MapInfoCategory.FOOD, List.of("TRADITIONAL_FOOD", "KOREAN_RESTAURANT", "RESTAURANT", "FOOD")),
            Map.entry(MapInfoCategory.CAFE, List.of("HANOK_CAFE", "TEA_HOUSE", "CAFE", "COFFEE_SHOP")),
            Map.entry(MapInfoCategory.MARKET, List.of("TRADITIONAL_MARKET", "MARKET", "LOCAL_MARKET")));

    private MapInfoCategoryMapping() {
    }

    public static List<String> applied(MapInfoCategory category) {
        if (category == null || category == MapInfoCategory.ALL) {
            return MAPPING.values().stream().flatMap(List::stream).distinct().toList();
        }
        return MAPPING.getOrDefault(category, List.of(category.name())).stream().distinct().toList();
    }

    public static String canonical(String value) {
        return value == null ? null : value.trim().toUpperCase(Locale.ROOT);
    }
}
