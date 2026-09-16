package com.yrootlab.onmaru.persistence.journey.run;

import com.yrootlab.onmaru.journey.run.ActiveRunConflictException;
import com.yrootlab.onmaru.journey.run.AdvanceRunStageCommand;
import com.yrootlab.onmaru.journey.run.ClaimRunCommand;
import com.yrootlab.onmaru.journey.run.CreateRunCommand;
import com.yrootlab.onmaru.journey.run.FinishRunCommand;
import com.yrootlab.onmaru.journey.run.JourneyRunSnapshot;
import com.yrootlab.onmaru.journey.run.JourneyRunStage;
import com.yrootlab.onmaru.journey.run.JourneyRunStatus;
import com.yrootlab.onmaru.journey.run.JourneyRunStore;
import com.yrootlab.onmaru.journey.run.RunCommandConflictException;
import com.yrootlab.onmaru.journey.run.RunCommandReceipt;
import com.yrootlab.onmaru.journey.run.RunCommandResult;
import com.yrootlab.onmaru.journey.run.RunNotFoundException;
import com.yrootlab.onmaru.journey.run.RunTransitionConflictException;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

public final class JdbcJourneyRunStore implements JourneyRunStore {

    private static final Duration COMMAND_TTL = Duration.ofHours(24);
    private final DataSource dataSource;

    public JdbcJourneyRunStore(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public RunCommandResult create(CreateRunCommand command) {
        return execute(command.actorKey(), "CREATE", command.commandKey(), command.requestHash(), connection -> {
            try (var statement = connection.prepareStatement("""
                    INSERT INTO onmaru.discovery_runs (
                        id, exploration_id, actor_key, base_version, status, stage, outcome,
                        created_at, deadline_at, started_at, generation, error_code, engine
                    ) VALUES (?, ?, ?, ?, 'QUEUED', NULL, NULL, ?, ?, NULL, 1, NULL, ?)
                    """)) {
                statement.setObject(1, command.runId());
                statement.setObject(2, command.explorationId());
                statement.setString(3, command.actorKey());
                statement.setInt(4, command.baseVersion());
                statement.setObject(5, utc(command.createdAt()));
                statement.setObject(6, utc(command.deadlineAt()));
                statement.setString(7, command.engine());
                statement.executeUpdate();
                return new RunCommandReceipt(command.runId(), JourneyRunStatus.QUEUED, null, null, 1);
            } catch (SQLException exception) {
                if (isActiveRunConflict(exception)) {
                    throw new ActiveRunConflictException();
                }
                throw exception;
            }
        });
    }

    @Override
    public RunCommandResult claim(ClaimRunCommand command) {
        return execute(command.actorKey(), "CLAIM", command.commandKey(), command.requestHash(), connection -> {
            try (var statement = connection.prepareStatement("""
                    UPDATE onmaru.discovery_runs
                    SET status = 'RUNNING', started_at = ?, generation = generation + 1
                    WHERE id = ? AND actor_key = ? AND status = 'QUEUED' AND generation = ?
                    RETURNING id, status::text, stage, outcome, generation
                    """)) {
                statement.setObject(1, utc(command.startedAt()));
                statement.setObject(2, command.runId());
                statement.setString(3, command.actorKey());
                statement.setInt(4, command.expectedGeneration());
                return requiredTransition(connection, statement.executeQuery(), command.runId(), command.actorKey());
            }
        });
    }

    @Override
    public RunCommandResult advance(AdvanceRunStageCommand command) {
        return execute(command.actorKey(), "ADVANCE_STAGE", command.commandKey(), command.requestHash(), connection -> {
            try (var statement = connection.prepareStatement("""
                    UPDATE onmaru.discovery_runs
                    SET stage = ?, generation = generation + 1
                    WHERE id = ? AND actor_key = ? AND status = 'RUNNING' AND generation = ?
                      AND stage IS NOT DISTINCT FROM ?
                    RETURNING id, status::text, stage, outcome, generation
                    """)) {
                statement.setString(1, command.nextStage().name());
                statement.setObject(2, command.runId());
                statement.setString(3, command.actorKey());
                statement.setInt(4, command.expectedGeneration());
                if (command.expectedStage() == null) {
                    statement.setNull(5, Types.VARCHAR);
                } else {
                    statement.setString(5, command.expectedStage().name());
                }
                return requiredTransition(connection, statement.executeQuery(), command.runId(), command.actorKey());
            }
        });
    }

    @Override
    public RunCommandResult finish(FinishRunCommand command) {
        return execute(command.actorKey(), "FINISH", command.commandKey(), command.requestHash(), connection -> {
            var allowedStatus = command.terminalStatus() == JourneyRunStatus.CANCELLED
                    ? "status IN ('QUEUED', 'RUNNING')"
                    : "status = 'RUNNING'";
            var sql = """
                    UPDATE onmaru.discovery_runs
                    SET status = ?::onmaru.discovery_run_status,
                        stage = NULL,
                        outcome = ?,
                        error_code = ?,
                        generation = generation + 1
                    WHERE id = ? AND actor_key = ? AND generation = ? AND %s
                    RETURNING id, status::text, stage, outcome, generation
                    """.formatted(allowedStatus);
            try (var statement = connection.prepareStatement(sql)) {
                statement.setString(1, command.terminalStatus().name());
                statement.setString(2, command.outcome());
                statement.setString(3, command.errorCode());
                statement.setObject(4, command.runId());
                statement.setString(5, command.actorKey());
                statement.setInt(6, command.expectedGeneration());
                return requiredFinishTransition(connection, statement.executeQuery(), command.runId(), command.actorKey());
            }
        });
    }

    @Override
    public Optional<JourneyRunSnapshot> find(UUID runId, String actorKey) {
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement("""
                     SELECT id, exploration_id, actor_key, base_version, status::text, stage, outcome,
                            created_at, deadline_at, started_at, generation, error_code, engine
                     FROM onmaru.discovery_runs
                     WHERE id = ? AND actor_key = ?
                     """)) {
            statement.setObject(1, runId);
            statement.setString(2, actorKey);
            try (var result = statement.executeQuery()) {
                return result.next() ? Optional.of(snapshot(result)) : Optional.empty();
            }
        } catch (SQLException exception) {
            throw databaseFailure(exception);
        }
    }

    private RunCommandResult execute(
            String actorKey,
            String operation,
            UUID commandKey,
            String requestHash,
            SqlMutation mutation) {
        try (var connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                lockCommand(connection, actorKey, operation, commandKey);
                var stored = findReceipt(connection, actorKey, operation, commandKey);
                if (stored.isPresent()) {
                    if (!stored.get().requestHash().equals(requestHash)) {
                        throw new RunCommandConflictException();
                    }
                    connection.commit();
                    return new RunCommandResult(stored.get().receipt(), true);
                }
                var receipt = mutation.apply(connection);
                insertReceipt(connection, actorKey, operation, commandKey, requestHash, receipt);
                connection.commit();
                return new RunCommandResult(receipt, false);
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

    private void lockCommand(Connection connection, String actorKey, String operation, UUID commandKey)
            throws SQLException {
        try (var statement = connection.prepareStatement(
                "SELECT pg_advisory_xact_lock(hashtextextended(?, 0))")) {
            statement.setString(1, actorKey + ":" + operation + ":" + commandKey);
            statement.execute();
        }
    }

    private Optional<StoredReceipt> findReceipt(
            Connection connection, String actorKey, String operation, UUID commandKey) throws SQLException {
        try (var statement = connection.prepareStatement("""
                SELECT request_hash, run_id, result_status::text, result_stage,
                       result_outcome, result_generation
                FROM onmaru.discovery_run_commands
                WHERE actor_key = ? AND operation = ? AND command_key = ?
                """)) {
            statement.setString(1, actorKey);
            statement.setString(2, operation);
            statement.setObject(3, commandKey);
            try (var result = statement.executeQuery()) {
                if (!result.next()) {
                    return Optional.empty();
                }
                return Optional.of(new StoredReceipt(result.getString("request_hash"), receipt(result)));
            }
        }
    }

    private void insertReceipt(
            Connection connection,
            String actorKey,
            String operation,
            UUID commandKey,
            String requestHash,
            RunCommandReceipt receipt) throws SQLException {
        var now = Instant.now();
        try (var statement = connection.prepareStatement("""
                INSERT INTO onmaru.discovery_run_commands (
                    actor_key, operation, command_key, request_hash, run_id,
                    result_status, result_stage, result_outcome, result_generation,
                    created_at, expires_at
                ) VALUES (?, ?, ?, ?, ?, ?::onmaru.discovery_run_status, ?, ?, ?, ?, ?)
                """)) {
            statement.setString(1, actorKey);
            statement.setString(2, operation);
            statement.setObject(3, commandKey);
            statement.setString(4, requestHash);
            statement.setObject(5, receipt.runId());
            statement.setString(6, receipt.status().name());
            statement.setString(7, receipt.stage() == null ? null : receipt.stage().name());
            statement.setString(8, receipt.outcome());
            statement.setInt(9, receipt.generation());
            statement.setObject(10, utc(now));
            statement.setObject(11, utc(now.plus(COMMAND_TTL)));
            statement.executeUpdate();
        }
    }

    private RunCommandReceipt requiredTransition(
            Connection connection, ResultSet result, UUID runId, String actorKey) throws SQLException {
        try (result) {
            if (result.next()) {
                return receiptFromTransition(result);
            }
        }
        if (findInTransaction(connection, runId, actorKey).isEmpty()) {
            throw new RunNotFoundException();
        }
        throw new RunTransitionConflictException();
    }

    private RunCommandReceipt requiredFinishTransition(
            Connection connection, ResultSet result, UUID runId, String actorKey) throws SQLException {
        try (result) {
            if (result.next()) {
                return receiptFromTransition(result);
            }
        }
        var current = findInTransaction(connection, runId, actorKey).orElseThrow(RunNotFoundException::new);
        if (current.status().isTerminal()) {
            return new RunCommandReceipt(
                    current.id(),
                    current.status(),
                    current.stage(),
                    current.outcome(),
                    current.generation());
        }
        throw new RunTransitionConflictException();
    }

    private Optional<JourneyRunSnapshot> findInTransaction(Connection connection, UUID runId, String actorKey)
            throws SQLException {
        try (var statement = connection.prepareStatement("""
                SELECT id, exploration_id, actor_key, base_version, status::text, stage, outcome,
                       created_at, deadline_at, started_at, generation, error_code, engine
                FROM onmaru.discovery_runs WHERE id = ? AND actor_key = ?
                """)) {
            statement.setObject(1, runId);
            statement.setString(2, actorKey);
            try (var result = statement.executeQuery()) {
                return result.next() ? Optional.of(snapshot(result)) : Optional.empty();
            }
        }
    }

    private RunCommandReceipt receipt(ResultSet result) throws SQLException {
        var stage = result.getString("result_stage");
        return new RunCommandReceipt(
                result.getObject("run_id", UUID.class),
                JourneyRunStatus.valueOf(result.getString("result_status")),
                stage == null ? null : JourneyRunStage.valueOf(stage),
                result.getString("result_outcome"),
                result.getInt("result_generation"));
    }

    private RunCommandReceipt receiptFromTransition(ResultSet result) throws SQLException {
        var stage = result.getString("stage");
        return new RunCommandReceipt(
                result.getObject("id", UUID.class),
                JourneyRunStatus.valueOf(result.getString("status")),
                stage == null ? null : JourneyRunStage.valueOf(stage),
                result.getString("outcome"),
                result.getInt("generation"));
    }

    private JourneyRunSnapshot snapshot(ResultSet result) throws SQLException {
        var stage = result.getString("stage");
        return new JourneyRunSnapshot(
                result.getObject("id", UUID.class),
                result.getObject("exploration_id", UUID.class),
                result.getString("actor_key"),
                result.getInt("base_version"),
                JourneyRunStatus.valueOf(result.getString("status")),
                stage == null ? null : JourneyRunStage.valueOf(stage),
                result.getString("outcome"),
                instant(result, "created_at"),
                instant(result, "deadline_at"),
                instant(result, "started_at"),
                result.getInt("generation"),
                result.getString("error_code"),
                result.getString("engine"));
    }

    private boolean isActiveRunConflict(SQLException exception) {
        return "23505".equals(exception.getSQLState())
                && (exception.getMessage().contains("discovery_runs_active_exploration_uq")
                || exception.getMessage().contains("discovery_runs_active_actor_uq"));
    }

    private RuntimeException databaseFailure(SQLException exception) {
        return new IllegalStateException("journey run persistence failed", exception);
    }

    private OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private Instant instant(ResultSet result, String column) throws SQLException {
        var value = result.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    @FunctionalInterface
    private interface SqlMutation {
        RunCommandReceipt apply(Connection connection) throws SQLException;
    }

    private record StoredReceipt(String requestHash, RunCommandReceipt receipt) {
    }
}
