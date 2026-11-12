package com.yrootlab.onmaru.journey.exploration;

import java.text.Normalizer;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

final class ExplorationIntakePolicy {

    private static final Pattern HTML = Pattern.compile(
            "<\\s*/?\\s*(script|iframe|img|svg|style|a|html|body)\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern PHONE = Pattern.compile("(?<!\\d)01[016789][-\\s]?\\d{3,4}[-\\s]?\\d{4}(?!\\d)");
    private static final Pattern EMAIL = Pattern.compile(
            "\\b[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern CONTROL_CHARACTER = Pattern.compile("\\p{Cc}");
    private static final List<Pattern> PROMPT_INJECTION = List.of(
            Pattern.compile("이전\\s*지시", Pattern.CASE_INSENSITIVE),
            Pattern.compile("시스템\\s*프롬프트", Pattern.CASE_INSENSITIVE),
            Pattern.compile("ignore\\s+(all\\s+)?previous\\s+instructions", Pattern.CASE_INSENSITIVE));
    private static final List<String> UNSUPPORTED = List.of(
            "시험", "답안", "숙제", "코딩", "주식", "법률", "의학", "날씨", "교통");
    private static final Map<String, String> REGIONS = Map.of(
            "전주", "kr-45",
            "서울", "kr-11",
            "경주", "kr-47",
            "안동", "kr-47",
            "부산", "kr-26");

    String validateCreate(String rawQuery) {
        var query = normalize(rawQuery);
        validateSafetyAndPrivacy(query);
        if (containsAny(query, UNSUPPORTED)) {
            throw new ExplorationInputRejectedException("JOURNEY_SCOPE_UNSUPPORTED");
        }
        return query;
    }

    String validateTurn(String rawQuery) {
        var query = normalize(rawQuery);
        validateSafetyAndPrivacy(query);
        if (containsAny(query, UNSUPPORTED)) {
            throw new ExplorationInputRejectedException("JOURNEY_SCOPE_UNSUPPORTED");
        }
        return query;
    }

    String detectRegion(String query) {
        for (var entry : REGIONS.entrySet()) {
            if (query.contains(entry.getKey())) {
                return entry.getValue();
            }
        }
        return null;
    }

    private void validateSafetyAndPrivacy(String query) {
        if (CONTROL_CHARACTER.matcher(query).find()
                || HTML.matcher(query).find()
                || PROMPT_INJECTION.stream().anyMatch(pattern -> pattern.matcher(query).find())) {
            throw new ExplorationInputRejectedException("SAFETY_BLOCKED");
        }
        if (PHONE.matcher(query).find() || EMAIL.matcher(query).find()) {
            throw new ExplorationInputRejectedException("PRIVACY_REDACT_REQUIRED");
        }
    }

    private String normalize(String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFC).trim();
    }

    private boolean containsAny(String value, List<String> keywords) {
        return keywords.stream().anyMatch(value::contains);
    }
}
