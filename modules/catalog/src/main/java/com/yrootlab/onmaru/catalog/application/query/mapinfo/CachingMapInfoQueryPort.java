package com.yrootlab.onmaru.catalog.application.query.mapinfo;

import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Snapshot-scoped bounded cache for public map reads. Member state is joined after this port. */
public final class CachingMapInfoQueryPort implements MapInfoQueryPort {
    private final MapInfoQueryPort delegate;
    private final Clock clock;
    private final long ttlMillis;
    private final int maxEntries;
    private final Map<MapInfoSqlQuery, Entry> entries = new ConcurrentHashMap<>();

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
        if (cached != null && now - cached.createdAtMillis < ttlMillis) return cached.value;
        var value = delegate.find(query);
        if (entries.size() >= maxEntries) entries.remove(entries.keySet().stream().findFirst().orElse(query));
        entries.put(query, new Entry(now, value));
        return value;
    }

    private record Entry(long createdAtMillis, MapInfoQueryResult value) { }
}
