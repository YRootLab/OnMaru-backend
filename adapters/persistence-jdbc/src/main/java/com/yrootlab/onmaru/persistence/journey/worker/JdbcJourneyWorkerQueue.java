package com.yrootlab.onmaru.persistence.journey.worker;

import com.yrootlab.onmaru.journey.worker.JourneyWorkerQueue;
import com.yrootlab.onmaru.journey.worker.JourneyWorkerRequest;

import javax.sql.DataSource;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

/**
 * Durable queue backed by discovery_runs itself. A QUEUED run row is the work item: it survives restarts and
 * is shared by every instance. Duplicate pickup is prevented by the worker's CAS claim, not by this poll.
 *
 * <p>poll() walks QUEUED runs in (created_at, id) order using a keyset cursor. When the walk is exhausted it
 * returns empty and resets the cursor, so one drain pass never revisits a run it already offered.
 */
public final class JdbcJourneyWorkerQueue implements JourneyWorkerQueue {

    /** Must match the catalog dataset used for approved places. */
    static final String DATASET = "kto-korean-tour";

    private static final String SELECT_NEXT = """
            SELECT r.id, r.exploration_id, r.actor_key, r.generation, r.base_version, r.deadline_at,
                   r.created_at, t.query, t.region_code,
                   (SELECT revision_id::text FROM onmaru.catalog_active_datasets
                     WHERE dataset = ?) AS dataset_revision
            FROM onmaru.discovery_runs r
            JOIN onmaru.discovery_turns t ON t.run_id = r.id
            WHERE r.status = 'QUEUED'
              AND r.deadline_at > ?
              AND (r.created_at, r.id) > (?, ?)
              AND EXISTS (SELECT 1 FROM onmaru.catalog_active_datasets active WHERE active.dataset = ?)
            ORDER BY r.created_at, r.id
            LIMIT 1
            """;

    private final DataSource dataSource;
    private final Clock clock;
    private Instant cursorCreatedAt;
    private UUID cursorRunId;

    public JdbcJourneyWorkerQueue(DataSource dataSource, Clock clock) {
        this.dataSource = dataSource;
        this.clock = clock;
    }

    @Override
    public void enqueue(JourneyWorkerRequest request) {
        // Queued runs are created by the exploration store together with their turn. Enqueuing a second copy
        // would duplicate work, so this is rejected instead of silently accepted.
        throw new UnsupportedOperationException("runs are queued by the exploration store, not enqueued directly");
    }

    @Override
    public synchronized Optional<JourneyWorkerRequest> poll() {
        Instant now = clock.instant();
        Instant afterCreatedAt = cursorCreatedAt == null ? Instant.EPOCH : cursorCreatedAt;
        UUID afterRunId = cursorRunId == null ? new UUID(0L, 0L) : cursorRunId;
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement(SELECT_NEXT)) {
            statement.setString(1, DATASET);
            statement.setObject(2, now.atOffset(ZoneOffset.UTC));
            statement.setObject(3, afterCreatedAt.atOffset(ZoneOffset.UTC));
            statement.setObject(4, afterRunId);
            statement.setString(5, DATASET);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    cursorCreatedAt = null;
                    cursorRunId = null;
                    return Optional.empty();
                }
                UUID runId = rows.getObject("id", UUID.class);
                cursorCreatedAt = rows.getObject("created_at", OffsetDateTime.class).toInstant();
                cursorRunId = runId;
                return Optional.of(new JourneyWorkerRequest(
                        runId,
                        rows.getObject("exploration_id", UUID.class),
                        rows.getString("actor_key"),
                        rows.getInt("generation"),
                        rows.getString("dataset_revision"),
                        rows.getString("region_code"),
                        rows.getString("query"),
                        rows.getInt("base_version"),
                        UUID.randomUUID().toString(),
                        UUID.randomUUID().toString().replace("-", ""),
                        rows.getObject("deadline_at", OffsetDateTime.class).toInstant()));
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("queued journey runs could not be polled", exception);
        }
    }
}
