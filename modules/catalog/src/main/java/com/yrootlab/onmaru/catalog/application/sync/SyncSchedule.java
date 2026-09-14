package com.yrootlab.onmaru.catalog.application.sync;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;

public record SyncSchedule(
        String dataset,
        ZoneId zoneId,
        LocalTime localTime,
        Instant nextDueAt,
        boolean enabled
) {

    public SyncSchedule {
        if (dataset == null || dataset.isBlank()) {
            throw new IllegalArgumentException("dataset must not be blank");
        }
        if (zoneId == null) {
            throw new IllegalArgumentException("zoneId must not be null");
        }
        if (localTime == null) {
            throw new IllegalArgumentException("localTime must not be null");
        }
        if (nextDueAt == null) {
            throw new IllegalArgumentException("nextDueAt must not be null");
        }
    }

    public SyncSchedule advanceAfter(Instant now) {
        LocalDate nextDate = LocalDate.ofInstant(now, zoneId);
        Instant candidate = nextDate.atTime(localTime).atZone(zoneId).toInstant();
        if (!candidate.isAfter(now)) {
            candidate = nextDate.plusDays(1).atTime(localTime).atZone(zoneId).toInstant();
        }
        return new SyncSchedule(dataset, zoneId, localTime, candidate, enabled);
    }
}
