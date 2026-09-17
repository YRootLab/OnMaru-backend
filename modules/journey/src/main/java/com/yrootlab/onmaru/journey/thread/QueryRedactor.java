package com.yrootlab.onmaru.journey.thread;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

public final class QueryRedactor {

    private static final Pattern EMAIL_PATTERN =
            Pattern.compile("[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}");
    private static final Pattern PHONE_PATTERN =
            Pattern.compile("(\\b01[016789]-?\\d{3,4}-?\\d{4}\\b)|(\\b\\d{2,3}-?\\d{3,4}-?\\d{4}\\b)");
    private static final Pattern SSN_PATTERN =
            Pattern.compile("\\b\\d{6}-[1-4]\\d{6}\\b");
    private static final Pattern TOKEN_PATTERN =
            Pattern.compile("\\b(Bearer\\s+[A-Za-z0-9._~+/-]+=*|ey[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}|[A-Za-z0-9]{32,64})\\b");

    private QueryRedactor() {
    }

    public record RedactionResult(String redactedText, List<String> flags) {
        public boolean hasRedactions() {
            return !flags.isEmpty();
        }
    }

    public static RedactionResult redact(String query) {
        if (query == null || query.isBlank()) {
            return new RedactionResult("", Collections.emptyList());
        }

        var flags = new ArrayList<String>();
        var result = query;

        if (SSN_PATTERN.matcher(result).find()) {
            flags.add("SSN");
            result = SSN_PATTERN.matcher(result).replaceAll("[REDACTED_SSN]");
        }

        if (EMAIL_PATTERN.matcher(result).find()) {
            flags.add("EMAIL");
            result = EMAIL_PATTERN.matcher(result).replaceAll("[REDACTED_EMAIL]");
        }

        if (PHONE_PATTERN.matcher(result).find()) {
            flags.add("PHONE");
            result = PHONE_PATTERN.matcher(result).replaceAll("[REDACTED_PHONE]");
        }

        if (TOKEN_PATTERN.matcher(result).find()) {
            flags.add("TOKEN");
            result = TOKEN_PATTERN.matcher(result).replaceAll("[REDACTED_TOKEN]");
        }

        return new RedactionResult(result, Collections.unmodifiableList(flags));
    }
}
