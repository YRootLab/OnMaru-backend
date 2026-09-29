package com.yrootlab.onmaru.catalog.application.query.mapinfo;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class MapInfoCacheTests {
    @Test
    void reusesSamePublicListQueryWithinSnapshotTtl() {
        var calls = new AtomicInteger();
        var snapshot = new MapInfoSnapshot("rev-1", Instant.EPOCH, "PUBLISHED");
        var delegate = (MapInfoQueryPort) query -> {
            calls.incrementAndGet();
            return new MapInfoQueryResult(snapshot, null, 0, List.of(), null, false);
        };
        var cache = new CachingMapInfoQueryPort(delegate, Duration.ofSeconds(30), 8,
                Clock.fixed(Instant.EPOCH, ZoneOffset.UTC));
        var query = new MapInfoSqlQuery("rev-1", List.of("SPOT"), null, null,
                "NAME", null, null, 30, null, null);

        cache.find(query);
        cache.find(query);

        assertThat(calls).hasValue(1);
    }
}
