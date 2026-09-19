package com.yrootlab.onmaru.persistence.journey.worker;

import com.yrootlab.onmaru.journey.worker.JourneyResultStore;
import com.yrootlab.onmaru.journey.worker.PersistJourneyResultCommand;
import com.yrootlab.onmaru.journey.worker.PersistJourneyResultResult;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.util.StringJoiner;

public final class JdbcJourneyResultStore implements JourneyResultStore {

    private final DataSource dataSource;

    public JdbcJourneyResultStore(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public PersistJourneyResultResult persist(PersistJourneyResultCommand command) {
        try (var connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                var updated = updateBoard(connection, command);
                if (updated) {
                    connection.commit();
                    return PersistJourneyResultResult.PERSISTED;
                }
                var result = classifyDiscard(connection, command);
                connection.commit();
                return result;
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

    private boolean updateBoard(java.sql.Connection connection, PersistJourneyResultCommand command)
            throws SQLException {
        try (var statement = connection.prepareStatement("""
                     UPDATE onmaru.discovery_explorations exploration
                     SET board = ?::jsonb,
                         state_version = state_version + 1,
                         updated_at = CURRENT_TIMESTAMP
                     WHERE exploration.id = ?
                       AND exploration.state_version = ?
                       AND EXISTS (
                           SELECT 1
                           FROM onmaru.discovery_runs run
                           WHERE run.id = ?
                             AND run.exploration_id = exploration.id
                             AND run.status = 'RUNNING'
                             AND run.deadline_at > CURRENT_TIMESTAMP
                       )
                     """)) {
            statement.setString(1, boardJson(command));
            statement.setObject(2, command.explorationId());
            statement.setInt(3, command.baseVersion());
            statement.setObject(4, command.runId());
            return statement.executeUpdate() == 1;
        }
    }

    private PersistJourneyResultResult classifyDiscard(
            java.sql.Connection connection,
            PersistJourneyResultCommand command) throws SQLException {
        try (var statement = connection.prepareStatement("""
                     SELECT status::text, deadline_at <= CURRENT_TIMESTAMP AS expired
                     FROM onmaru.discovery_runs
                     WHERE id = ? AND exploration_id = ?
                     """)) {
            statement.setObject(1, command.runId());
            statement.setObject(2, command.explorationId());
            try (var result = statement.executeQuery()) {
                if (result.next() && result.getBoolean("expired")) {
                    return PersistJourneyResultResult.DISCARDED_EXPIRED;
                }
                return PersistJourneyResultResult.DISCARDED_TERMINAL;
            }
        }
    }

    private String boardJson(PersistJourneyResultCommand command) {
        return """
                {"schemaVersion":"1.2","engine":"%s","degradedReason":%s,"outcome":"%s","orderedRefs":%s}
                """.formatted(
                command.engine().name(),
                command.degradedReason() == null ? "null" : "\"" + command.degradedReason().name() + "\"",
                jsonEscape(command.outcome()),
                refsJson(command));
    }

    private String refsJson(PersistJourneyResultCommand command) {
        var joiner = new StringJoiner(",", "[", "]");
        for (var ref : command.orderedRefs()) {
            joiner.add("\"" + jsonEscape(ref) + "\"");
        }
        return joiner.toString();
    }

    private String jsonEscape(String value) {
        return value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\b", "\\b")
                .replace("\f", "\\f")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    private RuntimeException databaseFailure(SQLException exception) {
        return new IllegalStateException("journey result persistence failed", exception);
    }
}
