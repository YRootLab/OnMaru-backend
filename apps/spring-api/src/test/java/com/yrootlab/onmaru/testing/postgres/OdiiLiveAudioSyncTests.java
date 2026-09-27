package com.yrootlab.onmaru.testing.postgres;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yrootlab.onmaru.audio.sync.OdiiRevisionSyncService;
import com.yrootlab.onmaru.audio.sync.OdiiSourceMapper;
import com.yrootlab.onmaru.audio.sync.OdiiSyncCommand;
import com.yrootlab.onmaru.audio.sync.OdiiSyncStatus;
import com.yrootlab.onmaru.catalog.application.sync.SyncRunLease;
import com.yrootlab.onmaru.tourism.audio.JdbcAudioRevisionStore;
import com.yrootlab.onmaru.tourism.audio.client.OdiiClientProperties;
import com.yrootlab.onmaru.tourism.audio.client.OdiiHttpClient;
import com.yrootlab.onmaru.tourism.audio.client.OdiiUriBuilder;
import com.yrootlab.onmaru.tourism.audio.mapping.OdiiSourceItemMapper;
import com.yrootlab.onmaru.tourism.audio.sync.OdiiStorySyncPageSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.net.URI;
import java.sql.Connection;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Explicitly enabled production-data smoke; never runs in the ordinary test suite or CI. */
@EnabledIfEnvironmentVariable(named = "ONMARU_LIVE_ODII_SYNC", matches = "true")
class OdiiLiveAudioSyncTests {

    private static final String DATASET = "odii-audio";
    private static final String OWNER = "manual-live-odii-sync";

    @Test
    void publishesTheCompleteCuratedKoreanAudioSnapshot() throws Exception {
        var dataSource = new DriverManagerDataSource(
                required("SPRING_DATASOURCE_URL"),
                required("SPRING_DATASOURCE_USERNAME"),
                required("SPRING_DATASOURCE_PASSWORD"));
        var store = new JdbcAudioRevisionStore(dataSource);
        UUID activeRevision = store.initializeDataset(DATASET, Instant.now());
        SyncRunLease lease = acquireLease(dataSource.getConnection());

        var source = new OdiiStorySyncPageSource(
                new OdiiHttpClient(new ObjectMapper(), OdiiClientProperties.defaults()),
                new OdiiUriBuilder(
                        URI.create("https://apis.data.go.kr/B551011/Odii"),
                        required("ONMARU_SECRET_ODII_SERVICE_KEY_CURRENT"),
                        "OnMaru"),
                new OdiiSourceItemMapper(),
                1000);
        var service = new OdiiRevisionSyncService(
                store, source, new OdiiSourceMapper(), List.of(), Clock.systemUTC(), null);

        var result = service.sync(new OdiiSyncCommand(
                DATASET, lease, activeRevision, List.of("ko"), false));

        assertThat(result.status()).isEqualTo(OdiiSyncStatus.PUBLISHED);
        assertThat(result.itemCount()).isGreaterThan(6_000);
        long storyCount;
        long spotCount;
        try (Connection connection = dataSource.getConnection()) {
            storyCount = count(connection, """
                    SELECT count(*)
                    FROM onmaru.audio_story_versions story
                    JOIN onmaru.catalog_active_datasets active
                      ON active.dataset = 'odii-audio' AND active.revision_id = story.revision_id
                    """);
            spotCount = count(connection, """
                    SELECT count(*)
                    FROM onmaru.audio_spot_versions spot
                    JOIN onmaru.catalog_active_datasets active
                      ON active.dataset = 'odii-audio' AND active.revision_id = spot.revision_id
                    """);
        }
        assertThat(storyCount + spotCount).isEqualTo(result.itemCount());

        System.out.printf("LIVE_ODII_SYNC revision=%s stories=%d spots=%d tombstones=%d%n",
                result.stagedRevisionId(), storyCount, spotCount, result.tombstoneCount());
    }

    private SyncRunLease acquireLease(Connection connection) throws Exception {
        try (connection; var statement = connection.prepareStatement("""
                INSERT INTO onmaru.operations_sync_leases (dataset, owner_token, generation, lease_until)
                VALUES (?, ?, 1, ?)
                ON CONFLICT (dataset) DO UPDATE SET
                    owner_token = EXCLUDED.owner_token,
                    generation = onmaru.operations_sync_leases.generation + 1,
                    lease_until = EXCLUDED.lease_until
                RETURNING generation
                """)) {
            statement.setString(1, DATASET);
            statement.setString(2, OWNER);
            statement.setObject(3, Instant.now().plus(1, ChronoUnit.HOURS).atOffset(ZoneOffset.UTC));
            try (var row = statement.executeQuery()) {
                assertThat(row.next()).isTrue();
                return new SyncRunLease(UUID.randomUUID(), DATASET, OWNER, row.getInt(1));
            }
        }
    }

    private long count(Connection connection, String sql) throws Exception {
        try (var statement = connection.createStatement(); var rows = statement.executeQuery(sql)) {
            rows.next();
            return rows.getLong(1);
        }
    }

    private String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException(name + " is required");
        return value;
    }
}
