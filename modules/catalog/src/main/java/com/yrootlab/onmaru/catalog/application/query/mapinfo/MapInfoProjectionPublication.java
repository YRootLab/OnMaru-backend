package com.yrootlab.onmaru.catalog.application.query.mapinfo;

import java.time.Instant;

/**
 * Public metadata for the read projection used by map-info responses.
 * It is deliberately metadata-only; publication and query implementations belong to later work items.
 */
public record MapInfoProjectionPublication(
        String projectionName,
        String revisionId,
        Instant publishedAt,
        String checksum,
        long rowCount,
        String mappingVersion) {
}
