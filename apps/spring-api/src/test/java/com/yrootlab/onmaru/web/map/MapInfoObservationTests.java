package com.yrootlab.onmaru.web.map;

import com.yrootlab.onmaru.catalog.application.query.mapinfo.MapInfoQueryPort;
import com.yrootlab.onmaru.catalog.application.query.mapinfo.MapInfoQueryResult;
import com.yrootlab.onmaru.catalog.application.query.mapinfo.MapInfoSqlQuery;
import com.yrootlab.onmaru.catalog.application.query.mapinfo.MapInfoSnapshot;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MapInfoObservationTests {

    @Test
    void recordsCoverageAndDatabaseDurationForSuccessfulListQueries() {
        var registry = new SimpleMeterRegistry();
        var observation = new MapInfoObservation(registry, Duration.ofMillis(500));
        var result = new MapInfoQueryResult(
                new MapInfoSnapshot("rev-1", Instant.EPOCH, "PUBLISHED"), null,
                1, List.of(), null, false);

        observation.observe((MapInfoQueryPort) query -> result, "places").find(emptyQuery());

        assertThat(registry.get("onmaru.map.info.coverage").tag("endpoint", "places")
                .tag("coverage", "COMPLETE").counter().count()).isEqualTo(1);
        assertThat(registry.get("onmaru.map.info.db.query.duration")
                .tag("endpoint", "places").timer().count()).isEqualTo(1);
    }

    @Test
    void recordsTimeoutAndPoolExhaustionSeparately() {
        var registry = new SimpleMeterRegistry();
        var observation = new MapInfoObservation(registry, Duration.ofMillis(500));
        var timeout = (MapInfoQueryPort) query -> {
            throw new IllegalStateException("query failed", new SQLException("canceling statement", "57014"));
        };
        var pool = (MapInfoQueryPort) query -> {
            throw new IllegalStateException("pool exhausted", new SQLException("too many clients", "53300"));
        };

        assertThatThrownBy(() -> observation.observe(timeout, "places").find(emptyQuery()))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> observation.observe(pool, "places").find(emptyQuery()))
                .isInstanceOf(IllegalStateException.class);

        assertThat(registry.get("onmaru.map.info.query.timeout").counter().count()).isEqualTo(1);
        assertThat(registry.get("onmaru.map.info.pool.exhausted").counter().count()).isEqualTo(1);
    }

    private MapInfoSqlQuery emptyQuery() {
        return new MapInfoSqlQuery(null, List.of(), null, null, "NAME", null, null, 30, null, null);
    }
}
