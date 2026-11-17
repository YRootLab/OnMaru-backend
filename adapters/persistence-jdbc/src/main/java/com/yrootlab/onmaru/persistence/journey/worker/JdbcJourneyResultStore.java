package com.yrootlab.onmaru.persistence.journey.worker;

import com.yrootlab.onmaru.journey.worker.JourneyResultStore;
import com.yrootlab.onmaru.journey.worker.PersistJourneyResultCommand;
import com.yrootlab.onmaru.journey.worker.PersistJourneyResultResult;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.StringJoiner;

public final class JdbcJourneyResultStore implements JourneyResultStore {

    private final DataSource dataSource;
    private final Clock clock;

    public JdbcJourneyResultStore(DataSource dataSource) {
        this(dataSource, Clock.systemUTC());
    }

    /**
     * Deadlines are judged with the same clock the worker uses; judging them with the database clock let the two
     * disagree and discard finished results. updated_at stays on the database clock so the row's timestamp
     * constraints hold even when the application clock is behind.
     */
    public JdbcJourneyResultStore(DataSource dataSource, Clock clock) {
        this.dataSource = dataSource;
        this.clock = clock;
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
                             AND run.deadline_at > ?
                       )
                     """)) {
            statement.setString(1, boardJson(command));
            statement.setObject(2, command.explorationId());
            statement.setInt(3, command.baseVersion());
            statement.setObject(4, command.runId());
            statement.setTimestamp(5, Timestamp.from(clock.instant()));
            return statement.executeUpdate() == 1;
        }
    }

    private PersistJourneyResultResult classifyDiscard(
            java.sql.Connection connection,
            PersistJourneyResultCommand command) throws SQLException {
        try (var statement = connection.prepareStatement("""
                     SELECT status::text, deadline_at <= ? AS expired
                     FROM onmaru.discovery_runs
                     WHERE id = ? AND exploration_id = ?
                     """)) {
            statement.setTimestamp(1, Timestamp.from(clock.instant()));
            statement.setObject(2, command.runId());
            statement.setObject(3, command.explorationId());
            try (var result = statement.executeQuery()) {
                if (result.next() && result.getBoolean("expired")) {
                    return PersistJourneyResultResult.DISCARDED_EXPIRED;
                }
                return PersistJourneyResultResult.DISCARDED_TERMINAL;
            }
        }
    }

    private String boardJson(PersistJourneyResultCommand command) {
        var json = new StringBuilder();
        json.append("{\"schemaVersion\":\"1.2\"")
                .append(",\"engine\":\"").append(command.engine().name()).append('"')
                .append(",\"degradedReason\":")
                .append(command.degradedReason() == null ? "null" : "\"" + command.degradedReason().name() + "\"")
                .append(",\"outcome\":\"").append(jsonEscape(command.outcome())).append('"')
                .append(",\"orderedRefs\":").append(refsJson(command));
        if (command.placeReasons() != null && !command.placeReasons().isEmpty()) {
            json.append(",\"placeReasons\":").append(placeReasonsJson(command.placeReasons()));
        }
        if (command.summaryText() != null && !command.summaryText().isBlank()) {
            json.append(",\"summaryText\":\"").append(jsonEscape(command.summaryText())).append('"');
        }
        json.append('}');
        return json.toString();
    }

    private String placeReasonsJson(java.util.Map<String, String> placeReasons) {
        var joiner = new StringJoiner(",", "{", "}");
        for (var entry : placeReasons.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null) {
                continue;
            }
            joiner.add("\"" + jsonEscape(entry.getKey()) + "\":\"" + jsonEscape(entry.getValue()) + "\"");
        }
        return joiner.toString();
    }

    private String refsJson(PersistJourneyResultCommand command) {
        var joiner = new StringJoiner(",", "[", "]");
        for (var ref : command.orderedRefs()) {
            joiner.add("\"" + jsonEscape(ref) + "\"");
        }
        return joiner.toString();
    }

    private String jsonEscape(String value) {
        if (value == null) {
            return "";
        }
        var escaped = new StringBuilder(value.length());
        for (int index = 0; index < value.length(); index++) {
            char ch = value.charAt(index);
            switch (ch) {
                case '\\' -> escaped.append("\\\\");
                case '"' -> escaped.append("\\\"");
                case '\b' -> escaped.append("\\b");
                case '\f' -> escaped.append("\\f");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                default -> {
                    if (ch < 0x20) {
                        escaped.append(String.format("\\u%04x", (int) ch));
                    } else {
                        escaped.append(ch);
                    }
                }
            }
        }
        return escaped.toString();
    }

    private RuntimeException databaseFailure(SQLException exception) {
        return new IllegalStateException("journey result persistence failed", exception);
    }
}
