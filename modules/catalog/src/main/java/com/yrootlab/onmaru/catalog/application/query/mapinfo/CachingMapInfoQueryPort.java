package com.yrootlab.onmaru.catalog.application.query.mapinfo;

import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/** Snapshot-scoped bounded cache for public map reads. Member state is joined after this port. */
public final class CachingMapInfoQueryPort implements MapInfoQueryPort {
    private final MapInfoQueryPort delegate;
    private final Clock clock;
    private final long ttlMillis;
    private final int maxEntries;
    private final Map<MapInfoSqlQuery, Entry> entries = new ConcurrentHashMap<>();
    private final LongAdder hits = new LongAdder();
    private final LongAdder misses = new LongAdder();

    public CachingMapInfoQueryPort(MapInfoQueryPort delegate, Duration ttl, int maxEntries) {
        this(delegate, ttl, maxEntries, Clock.systemUTC());
    }

    CachingMapInfoQueryPort(MapInfoQueryPort delegate, Duration ttl, int maxEntries, Clock clock) {
        if (ttl.isNegative() || ttl.isZero() || maxEntries < 1) throw new IllegalArgumentException("invalid cache policy");
        this.delegate = delegate;
        this.clock = clock;
        this.ttlMillis = ttl.toMillis();
        this.maxEntries = maxEntries;
    }

    @Override
    public MapInfoQueryResult find(MapInfoSqlQuery query) {
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

    private boolean snapshotMatches(MapInfoSqlQuery query, MapInfoQueryResult value) {
        if (query.snapshotId() != null) return query.snapshotId().equals(value.snapshot().id());
        if (!(delegate instanceof MapInfoSnapshotResolver resolver)) return true;
        return resolver.currentSnapshot().id().equals(value.snapshot().id());
    }

    private record Entry(long createdAtMillis, MapInfoQueryResult value) { }
}
