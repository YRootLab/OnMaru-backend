package com.yrootlab.onmaru.catalog.application.publication;

import java.time.Instant;

public record SourceWatermark(
        String sourceModifiedAt,
        String externalId,
        Instant lastSuccessAt
) {
}
