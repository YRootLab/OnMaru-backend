package com.yrootlab.onmaru.community.command.review;

import java.text.Normalizer;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

public final class VisitReviewTagPolicy {

    private static final Pattern CANONICAL = Pattern.compile("[가-힣a-z0-9]+(?: [가-힣a-z0-9]+)*");

    public List<String> normalize(List<String> rawTags) {
        if (rawTags == null) {
            return List.of();
        }
        if (rawTags.size() > 5) {
            throw error("TOO_MANY_TAGS", null);
        }
        var result = new LinkedHashSet<String>();
        for (int index = 0; index < rawTags.size(); index++) {
            var rawTag = rawTags.get(index);
            if (rawTag == null) {
                throw error("TAG_REQUIRED", index);
            }
            var value = Normalizer.normalize(rawTag.trim(), Normalizer.Form.NFC);
            if (value.startsWith("#")) {
                value = value.substring(1);
            }
            value = value.replaceAll(" +", " ").toLowerCase(Locale.ROOT);
            if (value.isBlank()) {
                throw error("TAG_REQUIRED", index);
            }
            if (value.codePointCount(0, value.length()) > 15) {
                throw error("TAG_TOO_LONG", index);
            }
            if (!CANONICAL.matcher(value).matches()) {
                throw error("INVALID_TAG_FORMAT", index);
            }
            result.add(value);
        }
        return List.copyOf(result);
    }

    private VisitReviewWarmthInvalidException error(String reason, Integer index) {
        return new VisitReviewWarmthInvalidException("tags", reason, index);
    }
}
