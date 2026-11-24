package com.yrootlab.onmaru.persistence.catalog;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public final class JdbcAiSourceRevocationStore {

    public static final String CONTRACT_VERSION = "source-revocation.v1";
    public static final int MAX_PAGE_SIZE = 500;

    private final DataSource dataSource;

    public JdbcAiSourceRevocationStore(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource must not be null");
    }

    public long watermark() {
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement(
                     "SELECT sequence FROM onmaru.ai_source_revocation_state WHERE id=1");
             var rows = statement.executeQuery()) {
            if (!rows.next()) throw new IllegalStateException("AI source revocation state is missing");
            return rows.getLong(1);
        } catch (SQLException exception) {
            throw new IllegalStateException("failed to read AI source revocation watermark", exception);
        }
    }

    public AiSourceRevocationPage eventsAfter(long afterSequence, int limit) {
        validate(afterSequence, limit);
        try (var connection = readSnapshot()) {
            long watermark = readWatermark(connection);
            if (afterSequence > watermark) throw new IllegalArgumentException("cursor exceeds watermark");
            var events = readEvents(connection, afterSequence, watermark, limit);
            connection.commit();
            return page(null, afterSequence, watermark, events);
        } catch (SQLException exception) {
            throw new IllegalStateException("failed to read AI source revocation events", exception);
        }
    }

    public AiSourceRevocationSnapshotData beginSnapshot() {
        try (var connection = dataSource.getConnection()) {
            connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            connection.setAutoCommit(false);
            long watermark = readWatermark(connection);
            UUID activeRevisionId;
            try (var statement = connection.prepareStatement("""
                    SELECT revision_id FROM onmaru.catalog_active_datasets WHERE dataset='kto-korean-tour'
                    """);
                 var rows = statement.executeQuery()) {
                if (!rows.next()) throw new IllegalStateException("active TourAPI revision is missing");
                activeRevisionId = rows.getObject(1, UUID.class);
            }
            UUID snapshotId = UUID.randomUUID();
            try (var statement = connection.prepareStatement("""
                    INSERT INTO onmaru.ai_source_revocation_snapshots
                        (snapshot_id, watermark, active_revision_id)
                    VALUES (?, ?, ?)
                    """)) {
                statement.setObject(1, snapshotId);
                statement.setLong(2, watermark);
                statement.setObject(3, activeRevisionId);
                statement.executeUpdate();
            }
            try (var statement = connection.prepareStatement("""
                    INSERT INTO onmaru.ai_source_revocation_snapshot_places(snapshot_id, public_id)
                    SELECT ?, public_id.public_id
                    FROM onmaru.catalog_place_versions place
                    JOIN onmaru.catalog_place_public_ids public_id USING (place_id)
                    WHERE place.revision_id=? AND place.status='ACTIVE'
                    ORDER BY public_id.public_id
                    """)) {
                statement.setObject(1, snapshotId);
                statement.setObject(2, activeRevisionId);
                statement.executeUpdate();
            }
            long publicPlaceCount;
            try (var statement = connection.prepareStatement("""
                    SELECT count(*) FROM onmaru.catalog_place_versions place
                    JOIN onmaru.catalog_place_public_ids public_id USING (place_id)
                    WHERE place.revision_id=? AND place.status='ACTIVE'
                    """)) {
                statement.setObject(1, activeRevisionId);
                try (var rows = statement.executeQuery()) {
                    rows.next();
                    publicPlaceCount = rows.getLong(1);
                }
            }
            connection.commit();
            return new AiSourceRevocationSnapshotData(snapshotId, watermark, activeRevisionId, publicPlaceCount);
        } catch (SQLException exception) {
            throw new IllegalStateException("failed to create AI source revocation snapshot", exception);
        }
    }

    public AiSourceRevocationPublicPlacePage snapshotPublicPlaces(
            UUID snapshotId, String afterPublicId, int limit) {
        Objects.requireNonNull(snapshotId, "snapshotId must not be null");
        validate(0, limit);
        String cursor = afterPublicId == null ? "" : afterPublicId;
        var ids = new ArrayList<String>();
        try (var connection = readSnapshot();
             var statement = connection.prepareStatement("""
                     SELECT public_id FROM onmaru.ai_source_revocation_snapshot_places
                     WHERE snapshot_id=? AND public_id > ? ORDER BY public_id LIMIT ?
                     """)) {
            statement.setObject(1, snapshotId);
            statement.setString(2, cursor);
            statement.setInt(3, limit + 1);
            try (var rows = statement.executeQuery()) {
                while (rows.next()) ids.add(rows.getString(1));
            }
            connection.commit();
            boolean hasMore = ids.size() > limit;
            if (hasMore) ids.remove(ids.size() - 1);
            return new AiSourceRevocationPublicPlacePage(
                    CONTRACT_VERSION, snapshotId.toString(), afterPublicId, List.copyOf(ids), hasMore);
        } catch (SQLException exception) {
            throw new IllegalStateException("failed to read AI source revocation snapshot places", exception);
        }
    }

    public AiSourceRevocationPage snapshotEvents(UUID snapshotId, long afterSequence, int limit) {
        Objects.requireNonNull(snapshotId, "snapshotId must not be null");
        validate(afterSequence, limit);
        try (var connection = readSnapshot()) {
            long watermark;
            try (var statement = connection.prepareStatement("""
                    SELECT watermark FROM onmaru.ai_source_revocation_snapshots WHERE snapshot_id=?
                    """)) {
                statement.setObject(1, snapshotId);
                try (var rows = statement.executeQuery()) {
                    if (!rows.next()) throw new IllegalArgumentException("snapshot not found");
                    watermark = rows.getLong(1);
                }
            }
            if (afterSequence > watermark) throw new IllegalArgumentException("cursor exceeds snapshot watermark");
            var events = readEvents(connection, afterSequence, watermark, limit);
            connection.commit();
            return page(snapshotId, afterSequence, watermark, events);
        } catch (SQLException exception) {
            throw new IllegalStateException("failed to read AI source revocation snapshot", exception);
        }
    }

    private Connection readSnapshot() throws SQLException {
        Connection connection = dataSource.getConnection();
        connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
        connection.setReadOnly(true);
        connection.setAutoCommit(false);
        return connection;
    }

    private static long readWatermark(Connection connection) throws SQLException {
        try (var statement = connection.prepareStatement(
                "SELECT sequence FROM onmaru.ai_source_revocation_state WHERE id=1");
             var rows = statement.executeQuery()) {
            if (!rows.next()) throw new IllegalStateException("AI source revocation state is missing");
            return rows.getLong(1);
        }
    }

    private static List<AiSourceRevocationEvent> readEvents(
            Connection connection, long afterSequence, long watermark, int limit) throws SQLException {
        var events = new ArrayList<AiSourceRevocationEvent>();
        try (var statement = connection.prepareStatement("""
                SELECT sequence, scope, source_type, source_id, source_revision, place_id,
                       revoked_at, reason_code
                FROM onmaru.ai_source_revocation_events
                WHERE sequence > ? AND sequence <= ?
                ORDER BY sequence
                LIMIT ?
                """)) {
            statement.setLong(1, afterSequence);
            statement.setLong(2, watermark);
            statement.setInt(3, limit);
            try (var rows = statement.executeQuery()) {
                while (rows.next()) {
                    events.add(new AiSourceRevocationEvent(
                            rows.getLong("sequence"), rows.getString("scope"),
                            rows.getString("source_type"), rows.getString("source_id"),
                            rows.getString("source_revision"), rows.getObject("place_id", UUID.class),
                            rows.getObject("revoked_at", java.time.OffsetDateTime.class),
                            rows.getString("reason_code")));
                }
            }
        }
        return List.copyOf(events);
    }

    private static AiSourceRevocationPage page(
            UUID snapshotId, long afterSequence, long watermark, List<AiSourceRevocationEvent> events) {
        return new AiSourceRevocationPage(
                CONTRACT_VERSION, snapshotId == null ? null : snapshotId.toString(), afterSequence,
                watermark, events, events.isEmpty() ? afterSequence < watermark
                        : events.get(events.size() - 1).sequence() < watermark);
    }

    private static void validate(long afterSequence, int limit) {
        if (afterSequence < 0) throw new IllegalArgumentException("afterSequence must be non-negative");
        if (limit < 1 || limit > MAX_PAGE_SIZE) throw new IllegalArgumentException("invalid page limit");
    }
}
