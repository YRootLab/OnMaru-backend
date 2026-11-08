package com.yrootlab.onmaru.integration.ai.grant;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yrootlab.onmaru.journey.worker.plan.AiJourneyContracts.CanonicalField;
import com.yrootlab.onmaru.journey.worker.plan.AiJourneyContracts.Filters;
import com.yrootlab.onmaru.journey.worker.plan.AiJourneyContracts.Versions;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.function.Function;
import java.util.UUID;

import static com.yrootlab.onmaru.integration.ai.grant.QueryGrantException.Code.*;

/**
 * PostgreSQL grant store. Only the SHA-256 token hash is persisted. consume() locks the grant and its run,
 * validates state and deadlines, runs the scoped operation, then marks the grant used in the same transaction,
 * so a failed scope check or lookup never burns the grant.
 */
public final class JdbcQueryGrantStore implements QueryGrantStore {

    private static final String UNIQUE_VIOLATION = "23505";
    private static final String FOREIGN_KEY_VIOLATION = "23503";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<List<CanonicalField>> FIELDS = new TypeReference<>() { };

    private final DataSource dataSource;

    public JdbcQueryGrantStore(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public void insert(QueryGrant grant) {
        final String sql = """
                INSERT INTO onmaru.journey_query_grants (
                    token_hash, run_id, plan_hash, filters, canonical_fields, versions,
                    max_places, created_at, expires_at
                ) VALUES (?, ?, ?, ?::jsonb, ?::jsonb, ?::jsonb, ?, ?, ?)
                """;
        try (Connection connection = dataSource.getConnection();
             var statement = connection.prepareStatement(sql)) {
            statement.setString(1, grant.tokenHash());
            statement.setObject(2, grant.runId());
            statement.setString(3, grant.planHash());
            statement.setString(4, JSON.writeValueAsString(grant.filters()));
            statement.setString(5, JSON.writeValueAsString(grant.canonicalFields()));
            statement.setString(6, JSON.writeValueAsString(grant.versions()));
            statement.setInt(7, grant.maxPlaces());
            statement.setObject(8, utc(grant.createdAt()));
            statement.setObject(9, utc(grant.expiresAt()));
            statement.executeUpdate();
        } catch (SQLException exception) {
            if (UNIQUE_VIOLATION.equals(exception.getSQLState())) {
                throw new QueryGrantException(GRANT_ALREADY_ISSUED);
            }
            if (FOREIGN_KEY_VIOLATION.equals(exception.getSQLState())) {
                throw new QueryGrantException(RUN_INACTIVE);
            }
            throw new IllegalStateException("query grant could not be stored", exception);
        } catch (Exception exception) {
            throw new IllegalStateException("query grant could not be serialized", exception);
        }
    }

    @Override
    public <T> T consume(String tokenHash, Instant now, Function<QueryGrant, T> operation) {
        try (Connection connection = dataSource.getConnection()) {
            boolean originalAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                T value = consumeInTransaction(connection, tokenHash, now, operation);
                connection.commit();
                return value;
            } catch (RuntimeException | SQLException exception) {
                connection.rollback();
                throw exception;
            } finally {
                connection.setAutoCommit(originalAutoCommit);
            }
        } catch (QueryGrantException | IllegalStateException exception) {
            throw exception;
        } catch (SQLException exception) {
            throw new IllegalStateException("query grant could not be consumed", exception);
        }
    }

    private <T> T consumeInTransaction(Connection connection, String tokenHash, Instant now,
                                       Function<QueryGrant, T> operation) throws SQLException {
        final String select = """
                SELECT g.token_hash, g.run_id, g.plan_hash, g.filters, g.canonical_fields, g.versions,
                       g.max_places, g.created_at, g.expires_at, g.consumed_at,
                       r.status::text AS run_status, r.deadline_at
                FROM onmaru.journey_query_grants g
                JOIN onmaru.discovery_runs r ON r.id = g.run_id
                WHERE g.token_hash = ?
                FOR UPDATE OF g, r
                """;
        QueryGrant grant;
        String runStatus;
        Instant runDeadline;
        Instant consumedAt;
        try (var statement = connection.prepareStatement(select)) {
            statement.setString(1, tokenHash);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    throw new QueryGrantException(GRANT_INVALID);
                }
                grant = map(rows);
                runStatus = rows.getString("run_status");
                runDeadline = instant(rows, "deadline_at");
                consumedAt = instantOrNull(rows, "consumed_at");
            }
        }

        if (consumedAt != null) {
            throw new QueryGrantException(GRANT_CONSUMED);
        }
        if (!grant.expiresAt().isAfter(now)) {
            throw new QueryGrantException(GRANT_EXPIRED);
        }
        if (!"RUNNING".equals(runStatus) || !runDeadline.isAfter(now)) {
            throw new QueryGrantException(RUN_INACTIVE);
        }

        T value = operation.apply(grant);

        try (var update = connection.prepareStatement("""
                UPDATE onmaru.journey_query_grants
                SET consumed_at = ?
                WHERE token_hash = ? AND consumed_at IS NULL
                """)) {
            update.setObject(1, utc(now));
            update.setString(2, tokenHash);
            if (update.executeUpdate() != 1) {
                throw new QueryGrantException(GRANT_CONSUMED);
            }
        }
        return value;
    }

    private static QueryGrant map(ResultSet rows) throws SQLException {
        try {
            return new QueryGrant(
                    rows.getString("token_hash"),
                    rows.getObject("run_id", UUID.class),
                    rows.getString("plan_hash"),
                    JSON.readValue(rows.getString("filters"), Filters.class),
                    JSON.readValue(rows.getString("canonical_fields"), FIELDS),
                    JSON.readValue(rows.getString("versions"), Versions.class),
                    rows.getInt("max_places"),
                    instant(rows, "created_at"),
                    instant(rows, "expires_at"));
        } catch (java.io.IOException exception) {
            throw new IllegalStateException("stored query grant is malformed", exception);
        }
    }

    private static Instant instant(ResultSet rows, String column) throws SQLException {
        return rows.getObject(column, OffsetDateTime.class).toInstant();
    }

    private static Instant instantOrNull(ResultSet rows, String column) throws SQLException {
        OffsetDateTime value = rows.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
