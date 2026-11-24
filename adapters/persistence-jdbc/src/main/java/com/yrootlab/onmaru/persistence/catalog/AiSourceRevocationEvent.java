package com.yrootlab.onmaru.persistence.catalog;

import java.time.OffsetDateTime;
import java.util.UUID;

public record AiSourceRevocationEvent(
        long sequence,
        String scope,
        String sourceType,
        String sourceId,
        String sourceRevision,
        UUID placeId,
        OffsetDateTime revokedAt,
        String reasonCode) { }
