package com.yrootlab.onmaru.persistence.insights;

import com.yrootlab.onmaru.insights.observation.ObservationMetric;
import com.yrootlab.onmaru.insights.observation.VisitorObservation;
import com.yrootlab.onmaru.insights.ingestion.DataLabVisitorRevisionWriter;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

/**
 * Atomically publishes a DataLab visitor snapshot.  A failed staging write rolls back before the
 * active dataset pointer can move, preserving the last successful revision (ADR-0015).
 */
public final class JdbcDataLabVisitorSnapshotPublisher implements DataLabVisitorRevisionWriter {

    public static final String DATASET = "kto-datalab-visitor";

    private final DataSource dataSource;

    public JdbcDataLabVisitorSnapshotPublisher(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public void replaceActive(List<VisitorObservation> observations) {
        if (observations == null || observations.isEmpty()) {
            throw new IllegalArgumentException("at least one observation is required");
        }
        var sourceObservedAt = observations.stream()
                .map(VisitorObservation::sourceObservedAt)
                .filter(java.util.Objects::nonNull)
                .max(Instant::compareTo)
                .orElseThrow(() -> new IllegalArgumentException("observations require sourceObservedAt"));
        publish(sourceObservedAt, observations);
    }

    public UUID publish(Instant sourceObservedAt, List<VisitorObservation> observations) {
        if (sourceObservedAt == null || observations == null || observations.isEmpty()) {
            throw new IllegalArgumentException("sourceObservedAt and at least one observation are required");
        }
        observations.forEach(this::requireValidObservation);

        try (var connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                lockDataset(connection);
                UUID baseRevisionId = findActiveRevisionId(connection);
                UUID revisionId = UUID.randomUUID();
                createStagingRevision(connection, revisionId, baseRevisionId, sourceObservedAt);
                for (VisitorObservation observation : observations) {
                    stageObservation(connection, revisionId, observation);
                }
                markPublished(connection, revisionId);
                activate(connection, revisionId);
                connection.commit();
                return revisionId;
            } catch (RuntimeException exception) {
                rollback(connection);
                throw exception;
            } catch (Exception exception) {
                rollback(connection);
                throw new IllegalStateException("Failed to publish DataLab visitor snapshot", exception);
            }
        } catch (RuntimeException exception) {
            throw exception;
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed to publish DataLab visitor snapshot", exception);
        }
    }

    private void lockDataset(Connection connection) throws SQLException {
        try (var statement = connection.prepareStatement("SELECT pg_advisory_xact_lock(hashtext(?))")) {
            statement.setString(1, DATASET);
            statement.execute();
        }
    }

    private UUID findActiveRevisionId(Connection connection) throws SQLException {
        try (var statement = connection.prepareStatement("""
                SELECT revision_id
                FROM onmaru.catalog_active_datasets
                WHERE dataset = ?
                """)) {
            statement.setString(1, DATASET);
            try (var result = statement.executeQuery()) {
                return result.next() ? result.getObject("revision_id", UUID.class) : null;
            }
        }
    }

    private void createStagingRevision(
            Connection connection,
            UUID revisionId,
            UUID baseRevisionId,
            Instant sourceObservedAt) throws SQLException {
        try (var statement = connection.prepareStatement("""
                INSERT INTO onmaru.catalog_dataset_revisions
                    (id, dataset, status, base_revision_id, source_observed_at, fetched_at)
                VALUES (?, ?, 'STAGING', ?, ?, now())
                """)) {
            statement.setObject(1, revisionId);
            statement.setString(2, DATASET);
            if (baseRevisionId == null) {
                statement.setNull(3, java.sql.Types.OTHER);
            } else {
                statement.setObject(3, baseRevisionId);
            }
            statement.setObject(4, OffsetDateTime.ofInstant(sourceObservedAt, ZoneOffset.UTC));
            statement.executeUpdate();
        }
    }

    private void stageObservation(Connection connection, UUID revisionId, VisitorObservation observation)
            throws SQLException {
        try (var statement = connection.prepareStatement("""
                INSERT INTO onmaru.insights_visitor_observations (
                    revision_id, region_id, basis_date, visitor_type, provider, spatial_level,
                    visitor_count, coverage_status, source_observed_at, fetched_at
                )
                SELECT ?, region.id, ?, 'TOTAL', ?, ?, ?, ?, ?, now()
                FROM onmaru.catalog_regions region
                WHERE region.code = ? AND region.active
                ON CONFLICT (revision_id, region_id, basis_date, visitor_type) DO UPDATE
                SET provider = EXCLUDED.provider,
                    spatial_level = EXCLUDED.spatial_level,
                    visitor_count = EXCLUDED.visitor_count,
                    coverage_status = EXCLUDED.coverage_status,
                    source_observed_at = EXCLUDED.source_observed_at,
                    fetched_at = EXCLUDED.fetched_at
                """)) {
            statement.setObject(1, revisionId);
            statement.setObject(2, observation.basisDate());
            statement.setString(3, observation.provider());
            statement.setString(4, observation.spatialLevel().name());
            if (observation.value() == null) {
                statement.setNull(5, java.sql.Types.BIGINT);
            } else {
                statement.setLong(5, observation.value());
            }
            statement.setString(6, observation.coverageStatus().name());
            statement.setObject(7, OffsetDateTime.ofInstant(observation.sourceObservedAt(), ZoneOffset.UTC));
            statement.setString(8, observation.regionCode());
            if (statement.executeUpdate() != 1) {
                throw new IllegalArgumentException("No active Catalog region for DataLab regionCode: "
                        + observation.regionCode());
            }
        }
    }

    private void markPublished(Connection connection, UUID revisionId) throws SQLException {
        try (var statement = connection.prepareStatement("""
                UPDATE onmaru.catalog_dataset_revisions
                SET status = 'PUBLISHED', published_at = now()
                WHERE id = ? AND dataset = ? AND status = 'STAGING'
                """)) {
            statement.setObject(1, revisionId);
            statement.setString(2, DATASET);
            if (statement.executeUpdate() != 1) {
                throw new IllegalStateException("Unable to publish staging DataLab revision");
            }
        }
    }

    private void activate(Connection connection, UUID revisionId) throws SQLException {
        try (var statement = connection.prepareStatement("""
                INSERT INTO onmaru.catalog_active_datasets (dataset, revision_id, activated_at)
                VALUES (?, ?, now())
                ON CONFLICT (dataset) DO UPDATE
                SET revision_id = EXCLUDED.revision_id, activated_at = EXCLUDED.activated_at
                """)) {
            statement.setString(1, DATASET);
            statement.setObject(2, revisionId);
            statement.executeUpdate();
        }
    }

    private void requireValidObservation(VisitorObservation observation) {
        if (observation == null || observation.metric() != ObservationMetric.VISITOR_COUNT) {
            throw new IllegalArgumentException("Only VISITOR_COUNT observations may be published");
        }
        if (!"persons".equals(observation.unit()) || observation.spatialLevel() == null
                || observation.spatialLevel().name().equals("UNKNOWN")) {
            throw new IllegalArgumentException("DataLab visitor observation has invalid unit or spatial level");
        }
        if (observation.value() != null && observation.value() < 0) {
            throw new IllegalArgumentException("DataLab visitor count must not be negative");
        }
        if (observation.regionCode() == null || observation.regionCode().isBlank()
                || observation.basisDate() == null || observation.provider() == null || observation.provider().isBlank()
                || observation.coverageStatus() == null || observation.sourceObservedAt() == null) {
            throw new IllegalArgumentException("DataLab visitor observation is missing required provenance");
        }
    }

    private void rollback(Connection connection) {
        try {
            connection.rollback();
        } catch (SQLException ignored) {
            // The connection closes immediately; preserve the original exception.
        }
    }
}
