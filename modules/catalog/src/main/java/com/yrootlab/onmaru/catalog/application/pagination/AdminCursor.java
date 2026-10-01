package com.yrootlab.onmaru.catalog.application.pagination;

import java.time.Instant;
import java.util.UUID;

public record AdminCursor(String resource, int limit, String filter, Instant timestamp, UUID id, String sortGroup) {
    public AdminCursor(String resource, int limit, String filter, Instant timestamp, UUID id) {
        this(resource, limit, filter, timestamp, id, null);
    }
}
