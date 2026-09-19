package com.yrootlab.onmaru.journey.timeline;

import java.time.Instant;
import java.time.YearMonth;
import java.util.UUID;

public record TimelineCursor(
        UUID memberId,
        YearMonth month,
        int limit,
        Instant asOf,
        Instant lastOccurredAt,
        String lastId) {
}
