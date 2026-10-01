package com.yrootlab.onmaru.catalog.application.query.mapinfo;

import java.time.Instant;

public record MapInfoSnapshot(
        String id,
        Instant publishedAt,
        String status) {
}
