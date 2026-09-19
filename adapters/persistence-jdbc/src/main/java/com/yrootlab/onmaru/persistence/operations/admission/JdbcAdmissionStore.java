package com.yrootlab.onmaru.persistence.operations.admission;

import com.yrootlab.onmaru.operations.admission.AdmissionDecision;
import com.yrootlab.onmaru.operations.admission.AdmissionRejectionReason;
import com.yrootlab.onmaru.operations.admission.AdmissionScope;
import com.yrootlab.onmaru.operations.admission.AdmissionStore;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

public final class JdbcAdmissionStore implements AdmissionStore {

    private final DataSource dataSource;

    public JdbcAdmissionStore(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public AdmissionDecision tryConsume(
            AdmissionScope scope,
            Instant windowStart,
            int limit,
            Duration retryAfter
    ) {
        var scopeKey = scopeKey(scope);
        try (var connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                ensureCounterRow(connection, scopeKey, windowStart);
                var counter = lockCounter(connection, scopeKey);
                var consumed = counter.windowStart().equals(windowStart) ? counter.consumed() : 0;
                var activeCount = counter.windowStart().equals(windowStart) ? counter.activeCount() : 0;
                var nextConsumed = consumed;
                AdmissionDecision decision;
                if (consumed >= limit) {
                    decision = AdmissionDecision.rejected(retryAfter);
                } else {
                    nextConsumed = consumed + 1;
                    decision = AdmissionDecision.allow();
                }
                updateCounter(connection, scopeKey, windowStart, nextConsumed, activeCount);
                insertAudit(connection, scope, scopeKey, windowStart, limit, nextConsumed, activeCount, decision);
                connection.commit();
                return decision;
            } catch (RuntimeException | SQLException exception) {
                connection.rollback();
                if (exception instanceof RuntimeException runtimeException) {
                    throw runtimeException;
                }
                throw databaseFailure((SQLException) exception);
            }
        } catch (SQLException exception) {
            throw databaseFailure(exception);
        }
    }

    @Override
    public AdmissionDecision tryStart(
            AdmissionScope scope,
            Instant windowStart,
            int limit,
            int activeLimit,
            Duration retryAfter,
            Duration activeRetryAfter
    ) {
        var scopeKey = scopeKey(scope);
        try (var connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                ensureCounterRow(connection, scopeKey, windowStart);
                var counter = lockCounter(connection, scopeKey);
                var consumed = counter.windowStart().equals(windowStart) ? counter.consumed() : 0;
                var activeCount = counter.windowStart().equals(windowStart) ? counter.activeCount() : 0;
                var nextConsumed = consumed;
                var nextActiveCount = activeCount;
                AdmissionDecision decision;
                if (consumed >= limit) {
                    decision = AdmissionDecision.rejected(retryAfter);
                } else if (activeCount >= activeLimit) {
                    decision = AdmissionDecision.rejected(activeRetryAfter, AdmissionRejectionReason.ACTIVE_LIMIT);
                } else {
                    nextConsumed = consumed + 1;
                    nextActiveCount = activeCount + 1;
                    decision = AdmissionDecision.allow();
                }
                updateCounter(connection, scopeKey, windowStart, nextConsumed, nextActiveCount);
                insertAudit(connection, scope, scopeKey, windowStart, limit, nextConsumed, nextActiveCount, decision);
                connection.commit();
                return decision;
            } catch (RuntimeException | SQLException exception) {
                connection.rollback();
                if (exception instanceof RuntimeException runtimeException) {
                    throw runtimeException;
                }
                throw databaseFailure((SQLException) exception);
            }
        } catch (SQLException exception) {
            throw databaseFailure(exception);
        }
    }

    @Override
    public void releaseActive(AdmissionScope scope) {
        var scopeKey = scopeKey(scope);
        try (var connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                var existing = findAndLockCounter(connection, scopeKey);
                if (existing != null && existing.activeCount() > 0) {
                    updateCounter(
                            connection,
                            scopeKey,
                            existing.windowStart(),
                            existing.consumed(),
                            existing.activeCount() - 1);
                }
                connection.commit();
            } catch (RuntimeException | SQLException exception) {
                connection.rollback();
                if (exception instanceof RuntimeException runtimeException) {
                    throw runtimeException;
                }
                throw databaseFailure((SQLException) exception);
            }
        } catch (SQLException exception) {
            throw databaseFailure(exception);
        }
    }

    private void ensureCounterRow(Connection connection, String scopeKey, Instant windowStart) throws SQLException {
        try (var statement = connection.prepareStatement("""
                INSERT INTO onmaru.operations_admission (scope_key, window_start, consumed, active_count)
                VALUES (?, ?, 0, 0)
                ON CONFLICT (scope_key) DO NOTHING
                """)) {
            statement.setString(1, scopeKey);
            statement.setObject(2, utc(windowStart));
            statement.executeUpdate();
        }
    }

    private Counter lockCounter(Connection connection, String scopeKey) throws SQLException {
        try (var statement = connection.prepareStatement("""
                SELECT window_start, consumed, active_count
                FROM onmaru.operations_admission
                WHERE scope_key = ?
                FOR UPDATE
                """)) {
            statement.setString(1, scopeKey);
            try (var result = statement.executeQuery()) {
                if (!result.next()) {
                    throw new IllegalStateException("admission counter row was not created");
                }
                return counter(result);
            }
        }
    }

    private Counter findAndLockCounter(Connection connection, String scopeKey) throws SQLException {
        try (var statement = connection.prepareStatement("""
                SELECT window_start, consumed, active_count
                FROM onmaru.operations_admission
                WHERE scope_key = ?
                FOR UPDATE
                """)) {
            statement.setString(1, scopeKey);
            try (var result = statement.executeQuery()) {
                return result.next() ? counter(result) : null;
            }
        }
    }

    private void updateCounter(
            Connection connection,
            String scopeKey,
            Instant windowStart,
            int consumed,
            int activeCount)
            throws SQLException {
        try (var statement = connection.prepareStatement("""
                UPDATE onmaru.operations_admission
                SET window_start = ?, consumed = ?, active_count = ?
                WHERE scope_key = ?
                """)) {
            statement.setObject(1, utc(windowStart));
            statement.setInt(2, consumed);
            statement.setInt(3, activeCount);
            statement.setString(4, scopeKey);
            statement.executeUpdate();
        }
    }

    private void insertAudit(
            Connection connection,
            AdmissionScope scope,
            String scopeKey,
            Instant windowStart,
            int limit,
            int consumedAfter,
            int activeAfter,
            AdmissionDecision decision
    ) throws SQLException {
        try (var statement = connection.prepareStatement("""
                INSERT INTO onmaru.operations_admission_audit (
                    scope_key, operation, subject_type, window_start, decision, reason,
                    limit_value, consumed_after, active_after, retry_after_ms
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            statement.setString(1, scopeKey);
            statement.setString(2, scope.operation());
            statement.setString(3, scope.subjectType().name());
            statement.setObject(4, utc(windowStart));
            statement.setString(5, decision.allowed() ? "ALLOWED" : "REJECTED");
            statement.setString(6, decision.reason() == null ? null : decision.reason().name());
            statement.setInt(7, limit);
            statement.setInt(8, consumedAfter);
            statement.setInt(9, activeAfter);
            statement.setLong(10, decision.retryAfter().toMillis());
            statement.executeUpdate();
        }
    }

    private Counter counter(ResultSet result) throws SQLException {
        return new Counter(
                result.getObject("window_start", OffsetDateTime.class).toInstant(),
                result.getInt("consumed"),
                result.getInt("active_count"));
    }

    private String scopeKey(AdmissionScope scope) {
        return scope.operation() + ":" + scope.subjectType() + ":" + scope.subjectKey();
    }

    private OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private IllegalStateException databaseFailure(SQLException exception) {
        return new IllegalStateException("admission persistence failed", exception);
    }

    private record Counter(Instant windowStart, int consumed, int activeCount) {
    }
}
