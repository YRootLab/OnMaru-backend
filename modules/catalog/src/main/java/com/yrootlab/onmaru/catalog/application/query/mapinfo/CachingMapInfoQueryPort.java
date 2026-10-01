package com.yrootlab.onmaru.catalog.application.query.mapinfo;

import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/** Snapshot-scoped bounded cache for public map reads. Member state is joined after this port. */
public final class CachingMapInfoQueryPort implements MapInfoQueryPort, MapInfoSnapshotResolver {
    private final MapInfoQueryPort delegate;
    private final Clock clock;
    private final long ttlMillis;
    private final long staleIfErrorMillis;
    private final int maxEntries;
    private final Map<MapInfoSqlQuery, Entry> entries = new ConcurrentHashMap<>();
    private final LongAdder hits = new LongAdder();
    private final LongAdder misses = new LongAdder();

    public CachingMapInfoQueryPort(MapInfoQueryPort delegate, Duration ttl, int maxEntries) {
        this(delegate, ttl, maxEntries, Duration.ofSeconds(30), Clock.systemUTC());
    }

    public CachingMapInfoQueryPort(MapInfoQueryPort delegate, Duration ttl, int maxEntries, Duration staleIfError) {
        this(delegate, ttl, maxEntries, staleIfError, Clock.systemUTC());
    }

    CachingMapInfoQueryPort(MapInfoQueryPort delegate, Duration ttl, int maxEntries, Duration staleIfError, Clock clock) {
        if (ttl.isNegative() || ttl.isZero() || maxEntries < 1) throw new IllegalArgumentException("invalid cache policy");
        this.delegate = delegate;
        this.clock = clock;
        this.ttlMillis = ttl.toMillis();
        this.staleIfErrorMillis = staleIfError.toMillis();
        this.maxEntries = maxEntries;
    }

    CachingMapInfoQueryPort(MapInfoQueryPort delegate, Duration ttl, int maxEntries, Clock clock) {
        this(delegate, ttl, maxEntries, Duration.ofSeconds(30), clock);
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
        MapInfoQueryResult value;
        try {
            value = delegate.find(query);
        } catch (RuntimeException exception) {
            if (cached != null && now - cached.createdAtMillis < ttlMillis + staleIfErrorMillis && snapshotMatches(query, cached.value)) {
                return cached.value.asStale();
            }
            throw exception;
        }
        if (entries.size() >= maxEntries) entries.remove(entries.keySet().stream().findFirst().orElse(query));
        entries.put(query, new Entry(now, value));
        return value;
    }

    public long hitCount() { return hits.sum(); }
    public long missCount() { return misses.sum(); }
    public int size() { return entries.size(); }

    @Override public MapInfoSnapshot currentSnapshot() {
        if (delegate instanceof MapInfoSnapshotResolver resolver) return resolver.currentSnapshot();
        throw new IllegalStateException("map info delegate cannot resolve active snapshot");
    }

    private boolean snapshotMatches(MapInfoSqlQuery query, MapInfoQueryResult value) {
        if (query.snapshotId() != null) return query.snapshotId().equals(value.snapshot().id());
        if (!(delegate instanceof MapInfoSnapshotResolver resolver)) return true;
        return resolver.currentSnapshot().id().equals(value.snapshot().id());
    }

    private record Entry(long createdAtMillis, MapInfoQueryResult value) { }
}
