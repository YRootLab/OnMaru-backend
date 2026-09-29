package com.yrootlab.onmaru.web.map;

import com.yrootlab.onmaru.catalog.application.query.mapinfo.MapInfoQueryPort;
import com.yrootlab.onmaru.catalog.application.query.mapinfo.MapInfoQueryResult;
import com.yrootlab.onmaru.catalog.application.query.mapinfo.MapInfoSnapshotResolver;
import com.yrootlab.onmaru.catalog.application.query.mapinfo.MapInfoViewportResponse;
import com.yrootlab.onmaru.catalog.application.query.mapinfo.MapInfoViewportStore;
import io.micrometer.core.instrument.MeterRegistry;

import java.sql.SQLException;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/** Records database latency, coverage, timeout, pool, and slow-query signals for map reads. */
public final class MapInfoObservation {
    private final MeterRegistry registry;
    private final Duration slowQueryThreshold;

    public MapInfoObservation(MeterRegistry registry, Duration slowQueryThreshold) {
        this.registry = Objects.requireNonNull(registry);
        this.slowQueryThreshold = Objects.requireNonNull(slowQueryThreshold);
    }

    public MapInfoQueryPort observe(MapInfoQueryPort delegate, String endpoint) {
        MapInfoQueryPort observed = query -> {
            long started = System.nanoTime();
            try {
                MapInfoQueryResult result = delegate.find(query);
                recordSuccess(endpoint, result.hasMore() ? "PARTIAL" : "COMPLETE", started);
                return result;
            } catch (RuntimeException exception) {
                recordFailure(endpoint, started, exception);
                throw exception;
            }
        };
        if (delegate instanceof MapInfoSnapshotResolver resolver) {
            return new ObservedQueryPort(observed, resolver);
        }
        return observed;
    }

    public MapInfoViewportStore observe(MapInfoViewportStore delegate, String endpoint) {
        MapInfoViewportStore observed = query -> {
            long started = System.nanoTime();
            try {
                MapInfoViewportResponse result = delegate.find(query);
                recordSuccess(endpoint, result.coverage(), started);
                return result;
            } catch (RuntimeException exception) {
                recordFailure(endpoint, started, exception);
                throw exception;
            }
        };
        if (delegate instanceof MapInfoSnapshotResolver resolver) {
            return new ObservedViewportStore(observed, resolver);
        }
        return observed;
    }

    private record ObservedQueryPort(MapInfoQueryPort delegate, MapInfoSnapshotResolver resolver)
            implements MapInfoQueryPort, MapInfoSnapshotResolver {
        @Override public MapInfoQueryResult find(com.yrootlab.onmaru.catalog.application.query.mapinfo.MapInfoSqlQuery query) { return delegate.find(query); }
        @Override public com.yrootlab.onmaru.catalog.application.query.mapinfo.MapInfoSnapshot currentSnapshot() { return resolver.currentSnapshot(); }
    }

    private record ObservedViewportStore(MapInfoViewportStore delegate, MapInfoSnapshotResolver resolver)
            implements MapInfoViewportStore, MapInfoSnapshotResolver {
        @Override public MapInfoViewportResponse find(com.yrootlab.onmaru.catalog.application.query.mapinfo.MapInfoViewportQuery query) { return delegate.find(query); }
        @Override public com.yrootlab.onmaru.catalog.application.query.mapinfo.MapInfoSnapshot currentSnapshot() { return resolver.currentSnapshot(); }
    }

    private void recordSuccess(String endpoint, String coverage, long started) {
        long elapsed = elapsed(started);
        registry.timer("onmaru.map.info.db.query.duration", "endpoint", endpoint)
                .record(elapsed, TimeUnit.NANOSECONDS);
        registry.counter("onmaru.map.info.coverage", "endpoint", endpoint, "coverage", coverage)
                .increment();
        if (elapsed >= slowQueryThreshold.toNanos()) {
            registry.counter("onmaru.map.info.slow.query", "endpoint", endpoint).increment();
        }
    }

    private void recordFailure(String endpoint, long started, RuntimeException exception) {
        long elapsed = elapsed(started);
        registry.timer("onmaru.map.info.db.query.duration", "endpoint", endpoint)
                .record(elapsed, TimeUnit.NANOSECONDS);
        if (isTimeout(exception)) {
            registry.counter("onmaru.map.info.query.timeout", "endpoint", endpoint).increment();
        }
        if (isPoolExhausted(exception)) {
            registry.counter("onmaru.map.info.pool.exhausted", "endpoint", endpoint).increment();
        }
        if (elapsed >= slowQueryThreshold.toNanos()) {
            registry.counter("onmaru.map.info.slow.query", "endpoint", endpoint).increment();
        }
    }

    private boolean isTimeout(Throwable throwable) {
        if (throwable instanceof MapInfoRequestTimeoutException) return true;
        SQLException sqlException = findSqlException(throwable);
        return sqlException != null && "57014".equals(sqlException.getSQLState());
    }

    private boolean isPoolExhausted(Throwable throwable) {
        SQLException sqlException = findSqlException(throwable);
        if (sqlException == null) return false;
        return "53300".equals(sqlException.getSQLState())
                || "08001".equals(sqlException.getSQLState())
                || "08004".equals(sqlException.getSQLState());
    }

    private SQLException findSqlException(Throwable throwable) {
        for (Throwable current = throwable; current != null; current = current.getCause()) {
            if (current instanceof SQLException sqlException) return sqlException;
        }
        return null;
    }

    private long elapsed(long started) {
        return System.nanoTime() - started;
    }
}
