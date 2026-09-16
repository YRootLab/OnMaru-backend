package com.yrootlab.onmaru.catalog.application.tags;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.Set;

public record ContentTagLexicon(
        Set<String> stopwords,
        Set<String> genericPhrases,
        Set<String> domainPhrases,
        Set<String> genericLabels
) {

    private static final String RESOURCE_ROOT = "content-tags/";

    public ContentTagLexicon {
        stopwords = copy(stopwords);
        genericPhrases = copy(genericPhrases);
        domainPhrases = copy(domainPhrases);
        genericLabels = copy(genericLabels);
    }

    public static ContentTagLexicon defaultLexicon() {
        return new ContentTagLexicon(
                load("stopwords.txt", fallbackStopwords()),
                load("generic-phrases.txt", fallbackGenericPhrases()),
                load("domain-phrases.txt", fallbackDomainPhrases()),
                load("generic-labels.txt", fallbackGenericLabels()));
    }

    private static Set<String> load(String resourceName, Set<String> fallback) {
        var stream = ContentTagLexicon.class.getClassLoader()
                .getResourceAsStream(RESOURCE_ROOT + resourceName);
        if (stream == null) {
            return fallback;
        }
        var values = new LinkedHashSet<String>();
        try (var reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String value = normalizeLine(line);
                if (!value.isBlank()) {
                    values.add(value);
                }
            }
        } catch (IOException exception) {
            throw new IllegalStateException("failed to load content tag lexicon: " + resourceName, exception);
        }
        return values.isEmpty() ? fallback : Set.copyOf(values);
    }

    private static String normalizeLine(String line) {
        int commentStart = line.indexOf('#');
        String uncommented = commentStart >= 0 ? line.substring(0, commentStart) : line;
        return uncommented.trim().toLowerCase();
    }

    private static Set<String> copy(Set<String> values) {
        return values == null ? Set.of() : Set.copyOf(values);
    }

    private static Set<String> fallbackStopwords() {
        return Set.of(
                "그리고", "또는", "입니다", "합니다", "있는", "없는", "있습니다", "없습니다",
                "관광지", "관광", "정보", "상세", "설명", "제공", "안내", "코스", "여행",
                "문화", "장소", "공개", "가능", "canonical");
    }

    private static Set<String> fallbackGenericPhrases() {
        return Set.of("상세 정보", "관광지 정보", "정보 안내", "상세 설명", "공개 가능");
    }

    private static Set<String> fallbackDomainPhrases() {
        return Set.of(
                "한옥 골목", "공예 체험", "야간 산책", "사진 명소", "역사 문화",
                "전통 공연", "먹거리 골목", "왕실 문화", "궁궐", "정원 산책",
                "전통 가옥", "한옥마을", "시장 음식", "전시 해설", "골목 산책");
    }

    private static Set<String> fallbackGenericLabels() {
        return Set.of(
                "관광", "정보", "소개", "안내", "코스", "여행", "관광지", "상세",
                "설명", "제공", "문화", "장소", "이야기");
    }
}
