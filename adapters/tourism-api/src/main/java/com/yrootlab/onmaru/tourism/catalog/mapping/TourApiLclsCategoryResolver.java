package com.yrootlab.onmaru.tourism.catalog.mapping;

import com.yrootlab.onmaru.catalog.application.qualification.CanonicalCategory;
import com.yrootlab.onmaru.catalog.application.qualification.SourceRecord;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

public final class TourApiLclsCategoryResolver {

    private final Map<String, Classification> classifications;

    private TourApiLclsCategoryResolver(Map<String, Classification> classifications) {
        this.classifications = Map.copyOf(classifications);
    }

    public static TourApiLclsCategoryResolver fromOfficialCodes(List<SourceRecord> codeRows) {
        Map<String, Classification> classifications = new LinkedHashMap<>();
        for (SourceRecord row : codeRows) {
            String level1Name = value(row, "lclsSystm1Nm", "lclssystm1nm");
            String level2Name = value(row, "lclsSystm2Nm", "lclssystm2nm");
            String level3Name = value(row, "lclsSystm3Nm", "lclssystm3nm");
            register(classifications, value(row, "lclsSystm1Cd", "lclssystm1cd"), level1Name, "", "");
            register(classifications, value(row, "lclsSystm2Cd", "lclssystm2cd"), level1Name, level2Name, "");
            register(classifications, value(row, "lclsSystm3Cd", "lclssystm3cd"), level1Name, level2Name, level3Name);
        }
        return new TourApiLclsCategoryResolver(classifications);
    }

    public Optional<CanonicalCategory> resolve(SourceRecord place) {
        return classification(place).flatMap(Classification::category);
    }

    public SourceRecord enrich(SourceRecord place) {
        var classification = classification(place);
        if (classification.isEmpty()) {
            return place;
        }
        var fields = new LinkedHashMap<>(place.fields());
        var value = classification.orElseThrow();
        fields.put("lclsSystm1Nm", value.level1Name());
        fields.put("lclsSystm2Nm", value.level2Name());
        fields.put("lclsSystm3Nm", value.level3Name());
        value.category().ifPresent(category -> fields.put("canonicalcategory", category.name()));
        return new SourceRecord(place.provider(), place.operation(), fields);
    }

    private Optional<Classification> classification(SourceRecord place) {
        for (String field : List.of("lclsSystm3", "lclsSystm2", "lclsSystm1",
                "lclssystm3", "lclssystm2", "lclssystm1")) {
            String code = place.field(field);
            if (code != null && classifications.containsKey(code)) {
                return Optional.of(classifications.get(code));
            }
        }
        return Optional.empty();
    }

    private static void register(
            Map<String, Classification> target,
            String code,
            String level1Name,
            String level2Name,
            String level3Name
    ) {
        if (code == null || code.isBlank()) return;
        target.put(code, new Classification(
                level1Name == null ? "" : level1Name,
                level2Name == null ? "" : level2Name,
                level3Name == null ? "" : level3Name,
                classify(level1Name, level2Name, level3Name)));
    }

    private static Optional<CanonicalCategory> classify(String level1, String level2, String level3) {
        String leaf = normalize(level3 == null || level3.isBlank() ? level2 : level3);
        String hierarchy = normalize(level1 + " " + level2 + " " + level3);

        if (containsAny(leaf, "전통찻집", "한옥카페")) return Optional.of(CanonicalCategory.HANOK_CAFE);
        if (hierarchy.contains("한옥") && containsAny(hierarchy, "숙박", "민박", "스테이"))
            return Optional.of(CanonicalCategory.HANOK_STAY);
        if (containsAny(leaf, "한옥", "고택", "전통가옥")) return Optional.of(CanonicalCategory.HANOK);
        if (containsAny(leaf, "전통문화체험", "전통체험")) return Optional.of(CanonicalCategory.HANOK_EXPERIENCE);
        if (containsAny(leaf, "전통시장", "상설시장", "오일장", "5일장", "공예", "공방"))
            return Optional.of(CanonicalCategory.TRADITIONAL_MARKET);
        if (containsAny(leaf, "박물관", "기념관", "전시관", "미술관"))
            return Optional.of(CanonicalCategory.CULTURE_ART);
        if (containsAny(leaf, "고궁", "궁궐", "성곽", "성터", "유적", "사찰", "탑", "종교성지", "묘소")
                || containsAny(normalize(level2), "역사", "유적"))
            return Optional.of(CanonicalCategory.HISTORIC_SITE);
        if (containsAny(leaf, "한식", "향토음식") || containsAny(normalize(level2), "한식", "향토음식"))
            return Optional.of(CanonicalCategory.TRADITIONAL_FOOD);
        if (containsAny(leaf, "생태", "휴양림", "수목원", "정원"))
            return Optional.of(CanonicalCategory.GARDEN_ECOLOGY);
        if (containsAny(leaf, "농촌체험", "관광농원", "어촌체험", "체험마을", "체험목장", "체험농장", "체험어장")
                || containsAny(normalize(level2), "농.산.어촌체험", "농산어촌체험"))
            return Optional.of(CanonicalCategory.LOCAL_SCENE);
        if (containsAny(normalize(level1), "자연") || containsAny(normalize(level2), "자연관광"))
            return Optional.of(CanonicalCategory.NATURE_SITE);
        if (containsAny(normalize(level1), "레포츠", "레저") || containsAny(normalize(level2), "레포츠", "레저"))
            return Optional.of(CanonicalCategory.LEISURE_ACTIVITY);
        return Optional.empty();
    }

    private static boolean containsAny(String value, String... fragments) {
        for (String fragment : fragments) if (value.contains(fragment)) return true;
        return false;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
    }

    private static String value(SourceRecord row, String first, String second) {
        String value = row.field(first);
        value = value == null ? row.field(second) : value;
        return value == null ? "" : value;
    }

    private record Classification(
            String level1Name,
            String level2Name,
            String level3Name,
            Optional<CanonicalCategory> category
    ) {
    }
}
