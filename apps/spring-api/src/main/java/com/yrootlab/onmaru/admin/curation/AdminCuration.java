package com.yrootlab.onmaru.admin.curation;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record AdminCuration(
        UUID id,
        UUID canonicalPlaceId,
        String category,
        boolean included,
        List<String> badges,
        UUID sourceRevisionId,
        long version,
        UUID updatedBy,
        Instant updatedAt) {
    public AdminCuration {
        badges = List.copyOf(badges == null ? List.of() : badges);
    }
}
