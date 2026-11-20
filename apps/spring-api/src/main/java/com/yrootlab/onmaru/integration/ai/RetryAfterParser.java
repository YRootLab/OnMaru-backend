package com.yrootlab.onmaru.integration.ai;

import java.net.http.HttpHeaders;
import java.time.Clock;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

final class RetryAfterParser {

    private static final long MAX_RETRY_AFTER_MS = 86_400_000L;

    private RetryAfterParser() {
    }

    static long parseMillis(HttpHeaders headers, Clock clock) {
        if (headers == null) {
            return 0L;
        }
        String raw = headers.firstValue("Retry-After").orElse(null);
        if (raw == null || raw.isBlank()) {
            return 0L;
        }
        String trimmed = raw.trim();
        try {
            long seconds = Long.parseLong(trimmed);
            if (seconds <= 0L) {
                return 0L;
            }
            if (seconds >= MAX_RETRY_AFTER_MS / 1000L) {
                return MAX_RETRY_AFTER_MS;
            }
            return seconds * 1000L;
        } catch (NumberFormatException ignored) {
            // Fall through to RFC-1123 HTTP-date parsing.
        }
        if (clock == null) {
            return 0L;
        }
        try {
            var retryAt = ZonedDateTime.parse(trimmed, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
            long millis = Duration.between(clock.instant(), retryAt).toMillis();
            if (millis <= 0L) {
                return 0L;
            }
            return Math.min(millis, MAX_RETRY_AFTER_MS);
        } catch (DateTimeParseException ignored) {
            return 0L;
        }
    }
}
