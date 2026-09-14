package com.yrootlab.onmaru.catalog.application.regionboundary;

import java.time.Instant;

public record RegionBoundarySourceManifest(
        String revisionId,
        String datasetUrl,
        String license,
        String attribution,
        Instant observedAt,
        String revisionHash
) {
}
