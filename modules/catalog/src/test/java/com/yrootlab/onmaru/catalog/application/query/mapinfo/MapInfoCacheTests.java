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
        assertThat(cache.hitCount()).isEqualTo(1);
        assertThat(cache.missCount()).isEqualTo(1);
        assertThat(cache.size()).isEqualTo(1);
    }

    @Test
    void invalidatesAnImplicitSnapshotEntryWhenPublicationChanges() {
        var calls = new AtomicInteger();
        var delegate = new SnapshotChangingPort(calls);
        var cache = new CachingMapInfoQueryPort(delegate, Duration.ofSeconds(30), 8,
                Clock.fixed(Instant.EPOCH, ZoneOffset.UTC));
        var query = new MapInfoSqlQuery(null, List.of("SPOT"), null, null,
                "NAME", null, null, 30, null, null);

        cache.find(query);
        delegate.snapshot = new MapInfoSnapshot("rev-2", Instant.EPOCH, "PUBLISHED");
        cache.find(query);

        assertThat(calls).hasValue(2);
        assertThat(cache.hitCount()).isZero();
        assertThat(cache.missCount()).isEqualTo(2);
    }

    @Test
    void servesAStaleSnapshotWhenTheDatabaseFailsWithinStaleIfErrorWindow() {
        var calls = new AtomicInteger();
        var snapshot = new MapInfoSnapshot("rev-1", Instant.EPOCH, "PUBLISHED");
        var delegate = (MapInfoQueryPort) query -> {
            if (calls.incrementAndGet() > 1) throw new IllegalStateException("database unavailable");
            return new MapInfoQueryResult(snapshot, null, 1, List.of(), null, false);
        };
        var now = new AtomicInteger();
        var clock = new Clock() {
            @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
            @Override public Clock withZone(java.time.ZoneId zone) { return this; }
            @Override public Instant instant() { return Instant.ofEpochSecond(now.get()); }
        };
        var cache = new CachingMapInfoQueryPort(delegate, Duration.ofSeconds(30), 8,
                Duration.ofSeconds(30), clock);
        var query = new MapInfoSqlQuery("rev-1", List.of(), null, null, "NAME", null, null, 30, null, null);

        cache.find(query);

        now.set(45);
        var stale = cache.find(query);
        assertThat(stale.coverage()).isEqualTo("STALE");
        assertThat(calls).hasValue(2);
    }

    private static final class SnapshotChangingPort implements MapInfoQueryPort, MapInfoSnapshotResolver {
        private final AtomicInteger calls;
        private MapInfoSnapshot snapshot = new MapInfoSnapshot("rev-1", Instant.EPOCH, "PUBLISHED");

        private SnapshotChangingPort(AtomicInteger calls) {
            this.calls = calls;
        }

        @Override
        public MapInfoSnapshot currentSnapshot() {
            return snapshot;
        }

        @Override
        public MapInfoQueryResult find(MapInfoSqlQuery query) {
            calls.incrementAndGet();
            return new MapInfoQueryResult(snapshot, null, 0, List.of(), null, false);
        }
    }
}
