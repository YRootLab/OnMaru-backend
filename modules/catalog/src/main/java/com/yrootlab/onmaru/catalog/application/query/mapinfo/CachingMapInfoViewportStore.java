package com.yrootlab.onmaru.catalog.application.query.mapinfo;

import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/** Snapshot/profile-scoped bounded cache for public viewport aggregates. */
public final class CachingMapInfoViewportStore implements MapInfoViewportStore {
    private final MapInfoViewportStore delegate;
    private final Clock clock;
    private final long ttlMillis;
    private final int maxEntries;
    private final Map<MapInfoViewportQuery, Entry> entries = new ConcurrentHashMap<>();
    private final LongAdder hits = new LongAdder();
    private final LongAdder misses = new LongAdder();

    public CachingMapInfoViewportStore(MapInfoViewportStore delegate, Duration ttl, int maxEntries) {
        this(delegate, ttl, maxEntries, Clock.systemUTC());
    }

    CachingMapInfoViewportStore(MapInfoViewportStore delegate, Duration ttl, int maxEntries, Clock clock) {
        if (ttl.isNegative() || ttl.isZero() || maxEntries < 1) throw new IllegalArgumentException("invalid cache policy");
        this.delegate = delegate;
        this.clock = clock;
        this.ttlMillis = ttl.toMillis();
        this.maxEntries = maxEntries;
    }

    @Override
    public MapInfoViewportResponse find(MapInfoViewportQuery query) {
        var now = clock.millis();
        var cached = entries.get(query);
        if (cached != null && now - cached.createdAtMillis < ttlMillis && snapshotMatches(query, cached.value)) {
            hits.increment();
            return cached.value;
        }
        misses.increment();
        var value = delegate.find(query);
        if (entries.size() >= maxEntries) entries.remove(entries.keySet().stream().findFirst().orElse(query));
        entries.put(query, new Entry(now, value));
        return value;
    }

    public long hitCount() { return hits.sum(); }
    public long missCount() { return misses.sum(); }
    public int size() { return entries.size(); }

    private boolean snapshotMatches(MapInfoViewportQuery query, MapInfoViewportResponse value) {
        if (query.snapshotId() != null) return query.snapshotId().equals(value.snapshot().id());
        if (!(delegate instanceof MapInfoSnapshotResolver resolver)) return true;
        return resolver.currentSnapshot().id().equals(value.snapshot().id());
    }

    private record Entry(long createdAtMillis, MapInfoViewportResponse value) { }
}
