package com.yrootlab.onmaru.persistence.journey.exploration;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yrootlab.onmaru.journey.exploration.ExplorationActor;
import com.yrootlab.onmaru.journey.exploration.ExplorationActorType;
import com.yrootlab.onmaru.journey.exploration.ExplorationNotFoundException;
import com.yrootlab.onmaru.journey.exploration.ExplorationPage;
import com.yrootlab.onmaru.journey.exploration.ExplorationRun;
import com.yrootlab.onmaru.journey.exploration.ExplorationRunOutcome;
import com.yrootlab.onmaru.journey.exploration.ExplorationRunStatus;
import com.yrootlab.onmaru.journey.exploration.ExplorationState;
import com.yrootlab.onmaru.journey.exploration.ExplorationStore;
import com.yrootlab.onmaru.journey.exploration.ExplorationSummary;
import com.yrootlab.onmaru.journey.exploration.ExplorationTurn;
import com.yrootlab.onmaru.journey.exploration.ExplorationTurnPage;
import com.yrootlab.onmaru.journey.exploration.StoredExplorationTurn;
import com.yrootlab.onmaru.journey.exploration.TurnResponse;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public final class JdbcExplorationStore implements ExplorationStore {

    private static final Duration GUEST_RETENTION = Duration.ofHours(24);
    private final DataSource dataSource;
    private final ObjectMapper objectMapper;

    public JdbcExplorationStore(DataSource dataSource, ObjectMapper objectMapper) {
        this.dataSource = dataSource;
        this.objectMapper = objectMapper;
    }

    @Override
    public void create(ExplorationState state, UUID initialTurnId, String query, Instant createdAt) {
        try (var connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                insertExploration(connection, state, query, createdAt);
                if (state.latestRun() != null) {
                    insertRun(connection, state.id(), state.owner(), state.latestRun());
                }
                insertTurn(connection, state.id(), new StoredExplorationTurn(
                        initialTurnId,
                        state.stateVersion(),
                        query,
                        state.regionCode(),
                        null,
                        state.latestRun(),
                        createdAt
                ));
                connection.commit();
            } catch (SQLException exception) {
                connection.rollback();
                throw new RuntimeException("Failed to create exploration", exception);
            }
        } catch (SQLException exception) {
            throw new RuntimeException("Database connection failure", exception);
        }
    }

    @Override
    public Optional<ExplorationState> find(UUID explorationId) {
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement("""
                     SELECT e.id, e.owner_member_id, e.owner_guest_id, e.state_version, e.region_id,
                            e.expires_at, e.deleted_at, e.created_at, e.updated_at,
                            r.id AS run_id, r.status AS run_status, r.engine AS run_engine,
                            r.stage AS run_stage, r.outcome AS run_outcome,
                            r.created_at AS run_created_at, r.started_at AS run_started_at,
                            r.deadline_at AS run_deadline_at
                     FROM onmaru.discovery_explorations e
                     LEFT JOIN LATERAL (
                         SELECT * FROM onmaru.discovery_runs
                         WHERE exploration_id = e.id
                         ORDER BY created_at DESC
                         LIMIT 1
                     ) r ON true
                     WHERE e.id = ? AND e.deleted_at IS NULL
                     """)) {
            statement.setObject(1, explorationId);
            try (var rs = statement.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of(mapState(rs));
            }
        } catch (SQLException exception) {
            throw new RuntimeException("Failed to find exploration", exception);
        }
    }

    @Override
    public Optional<StoredExplorationTurn> findTurn(UUID explorationId, UUID clientTurnId) {
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement("""
                     SELECT t.client_turn_id, t.base_version, t.query, t.region_code,
                            t.clarification_id, t.created_at,
                            r.id AS run_id, r.status AS run_status, r.engine AS run_engine,
                            r.stage AS run_stage, r.outcome AS run_outcome,
                            r.created_at AS run_created_at, r.started_at AS run_started_at,
                            r.deadline_at AS run_deadline_at
                     FROM onmaru.discovery_turns t
                     LEFT JOIN onmaru.discovery_runs r ON r.id = t.run_id
                     WHERE t.exploration_id = ? AND t.client_turn_id = ?
                     """)) {
            statement.setObject(1, explorationId);
            statement.setObject(2, clientTurnId);
            try (var rs = statement.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of(mapTurn(rs));
            }
        } catch (SQLException exception) {
            throw new RuntimeException("Failed to find exploration turn", exception);
        }
    }

    @Override
    public void appendTurn(UUID explorationId, StoredExplorationTurn turn) {
        try (var connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                if (turn.run() != null) {
                    var actor = findOwner(connection, explorationId);
                    insertRun(connection, explorationId, actor, turn.run());
                }
                insertTurn(connection, explorationId, turn);
                connection.commit();
            } catch (SQLException exception) {
                connection.rollback();
                throw new RuntimeException("Failed to append turn", exception);
            }
        } catch (SQLException exception) {
            throw new RuntimeException("Database connection failure", exception);
        }
    }

    @Override
    public void update(ExplorationState state) {
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement("""
                     UPDATE onmaru.discovery_explorations
                     SET state_version = ?, updated_at = ?
                     WHERE id = ? AND deleted_at IS NULL
                     """)) {
            statement.setInt(1, state.stateVersion());
            statement.setTimestamp(2, Timestamp.from(state.updatedAt()));
            statement.setObject(3, state.id());
            statement.executeUpdate();
        } catch (SQLException exception) {
            throw new RuntimeException("Failed to update exploration state", exception);
        }
    }

    @Override
    public ExplorationRun claimRun(UUID explorationId, UUID runId, Instant startedAt, String stage) {
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement("""
                     UPDATE onmaru.discovery_runs
                     SET status = 'RUNNING', stage = ?, started_at = ?
                     WHERE id = ? AND exploration_id = ? AND status = 'QUEUED'
                     RETURNING id, engine, created_at, deadline_at
                     """)) {
            statement.setString(1, stage);
            statement.setTimestamp(2, Timestamp.from(startedAt));
            statement.setObject(3, runId);
            statement.setObject(4, explorationId);
            try (var rs = statement.executeQuery()) {
                if (rs.next()) {
                    return new ExplorationRun(
                            runId,
                            ExplorationRunStatus.RUNNING,
                            rs.getString("engine"),
                            stage,
                            null,
                            null,
                            rs.getTimestamp("created_at").toInstant(),
                            startedAt,
                            rs.getTimestamp("deadline_at").toInstant()
                    );
                }
            }
        } catch (SQLException exception) {
            throw new RuntimeException("Failed to claim run", exception);
        }
        return findRun(explorationId, runId).orElseThrow(ExplorationNotFoundException::new);
    }

    @Override
    public ExplorationRun finishRun(
            UUID explorationId,
            UUID runId,
            ExplorationRunStatus terminalStatus,
            ExplorationRunOutcome outcome,
            Instant finishedAt) {
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement("""
                     UPDATE onmaru.discovery_runs
                     SET status = ?::onmaru.discovery_run_status, outcome = ?, stage = NULL
                     WHERE id = ? AND exploration_id = ? AND status IN ('QUEUED', 'RUNNING')
                     RETURNING id, engine, created_at, started_at, deadline_at
                     """)) {
            statement.setString(1, terminalStatus.name());
            statement.setString(2, outcome != null ? outcome.name() : null);
            statement.setObject(3, runId);
            statement.setObject(4, explorationId);
            try (var rs = statement.executeQuery()) {
                if (rs.next()) {
                    return new ExplorationRun(
                            runId,
                            terminalStatus,
                            rs.getString("engine"),
                            null,
                            outcome,
                            null,
                            rs.getTimestamp("created_at").toInstant(),
                            rs.getTimestamp("started_at") != null ? rs.getTimestamp("started_at").toInstant() : null,
                            rs.getTimestamp("deadline_at").toInstant()
                    );
                }
            }
        } catch (SQLException exception) {
            throw new RuntimeException("Failed to finish run", exception);
        }
        return findRun(explorationId, runId).orElseThrow(ExplorationNotFoundException::new);
    }

    @Override
    public ExplorationPage list(ExplorationActor actor, int limit, String cursor, Instant now) {
        var queryLimit = Math.min(Math.max(limit, 1), 50);
        var cursorTime = decodeCursor(cursor);

        String sql = """
                SELECT e.id, e.title, e.last_user_query_preview, e.updated_at,
                       r.status AS latest_run_status
                FROM onmaru.discovery_explorations e
                LEFT JOIN LATERAL (
                    SELECT status FROM onmaru.discovery_runs
                    WHERE exploration_id = e.id
                    ORDER BY created_at DESC
                    LIMIT 1
                ) r ON true
                WHERE e.deleted_at IS NULL
                  AND (e.expires_at IS NULL OR e.expires_at > ?)
                  AND (%s = ?)
                  AND (?::timestamptz IS NULL OR e.updated_at < ?::timestamptz)
                ORDER BY e.updated_at DESC, e.id DESC
                LIMIT ?
                """.formatted(actor.type() == ExplorationActorType.MEMBER ? "e.owner_member_id" : "e.owner_guest_id");

        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement(sql)) {
            statement.setTimestamp(1, Timestamp.from(now));
            statement.setObject(2, UUID.fromString(actor.subject()));
            if (cursorTime != null) {
                statement.setTimestamp(3, Timestamp.from(cursorTime));
                statement.setTimestamp(4, Timestamp.from(cursorTime));
            } else {
                statement.setNull(3, Types.TIMESTAMP);
                statement.setNull(4, Types.TIMESTAMP);
            }
            statement.setInt(5, queryLimit + 1);

            var items = new ArrayList<ExplorationSummary>();
            try (var rs = statement.executeQuery()) {
                while (rs.next()) {
                    var id = (UUID) rs.getObject("id");
                    var title = rs.getString("title");
                    if (title == null || title.isBlank()) {
                        title = "여정 탐색";
                    }
                    var queryPreview = rs.getString("last_user_query_preview");
                    var runStatusStr = rs.getString("latest_run_status");
                    ExplorationRunStatus runStatus = runStatusStr != null ? ExplorationRunStatus.valueOf(runStatusStr) : null;
                    var updatedAt = rs.getTimestamp("updated_at").toInstant();
                    items.add(new ExplorationSummary(id, title, queryPreview, runStatus, 0, 0, updatedAt));
                }
            }

            boolean hasMore = items.size() > queryLimit;
            if (hasMore) {
                items.remove(items.size() - 1);
            }
            String nextCursor = null;
            if (hasMore && !items.isEmpty()) {
                nextCursor = encodeCursor(items.get(items.size() - 1).updatedAt());
            }

            long totalCount = countExplorations(connection, actor, now);
            return new ExplorationPage(items, totalCount, nextCursor, hasMore);
        } catch (SQLException exception) {
            throw new RuntimeException("Failed to list explorations", exception);
        }
    }

    @Override
    public ExplorationTurnPage listTurns(UUID explorationId, int limit, String cursor) {
        var queryLimit = Math.min(Math.max(limit, 1), 50);
        var cursorTime = decodeCursor(cursor);

        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement("""
                     SELECT t.client_turn_id, t.query, t.created_at, t.turn_response,
                            r.id AS run_id, r.status AS run_status, r.outcome AS run_outcome
                     FROM onmaru.discovery_turns t
                     LEFT JOIN onmaru.discovery_runs r ON r.id = t.run_id
                     WHERE t.exploration_id = ?
                       AND (?::timestamptz IS NULL OR t.created_at < ?::timestamptz)
                     ORDER BY t.created_at DESC, t.id DESC
                     LIMIT ?
                     """)) {
            statement.setObject(1, explorationId);
            if (cursorTime != null) {
                statement.setTimestamp(2, Timestamp.from(cursorTime));
                statement.setTimestamp(3, Timestamp.from(cursorTime));
            } else {
                statement.setNull(2, Types.TIMESTAMP);
                statement.setNull(3, Types.TIMESTAMP);
            }
            statement.setInt(4, queryLimit + 1);

            var items = new ArrayList<ExplorationTurn>();
            try (var rs = statement.executeQuery()) {
                while (rs.next()) {
                    var turnId = (UUID) rs.getObject("client_turn_id");
                    var query = rs.getString("query");
                    var createdAt = rs.getTimestamp("created_at").toInstant();
                    var runId = (UUID) rs.getObject("run_id");
                    var responseJson = rs.getString("turn_response");
                    var runStatus = rs.getString("run_status");
                    var runOutcome = rs.getString("run_outcome");

                    TurnResponse turnResponse = parseTurnResponse(responseJson, runStatus, runOutcome);
                    items.add(new ExplorationTurn(turnId, query, createdAt, runId, turnResponse));
                }
            }

            boolean hasMore = items.size() > queryLimit;
            if (hasMore) {
                items.remove(items.size() - 1);
            }
            String nextCursor = null;
            if (hasMore && !items.isEmpty()) {
                nextCursor = encodeCursor(items.get(items.size() - 1).createdAt());
            }

            return new ExplorationTurnPage(items, nextCursor, hasMore);
        } catch (SQLException exception) {
            throw new RuntimeException("Failed to list turns", exception);
        }
    }

    @Override
    public boolean delete(ExplorationActor actor, UUID explorationId, Instant deletedAt) {
        String ownerCol = actor.type() == ExplorationActorType.MEMBER ? "owner_member_id" : "owner_guest_id";
        String sql = """
                UPDATE onmaru.discovery_explorations
                SET deleted_at = ?
                WHERE id = ? AND %s = ? AND deleted_at IS NULL
                """.formatted(ownerCol);

        try (var connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try (var statement = connection.prepareStatement(sql)) {
                statement.setTimestamp(1, Timestamp.from(deletedAt));
                statement.setObject(2, explorationId);
                statement.setObject(3, UUID.fromString(actor.subject()));
                int updated = statement.executeUpdate();
                if (updated > 0) {
                    cancelActiveRuns(connection, explorationId);
                    connection.commit();
                    return true;
                }
                connection.commit();
                return false;
            } catch (SQLException exception) {
                connection.rollback();
                throw new RuntimeException("Failed to delete exploration", exception);
            }
        } catch (SQLException exception) {
            throw new RuntimeException("Database failure", exception);
        }
    }

    @Override
    public void deleteExpiredGuests(Instant now) {
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement("""
                     UPDATE onmaru.discovery_explorations
                     SET deleted_at = ?
                     WHERE owner_guest_id IS NOT NULL
                       AND expires_at IS NOT NULL
                       AND expires_at <= ?
                       AND deleted_at IS NULL
                     """)) {
            statement.setTimestamp(1, Timestamp.from(now));
            statement.setTimestamp(2, Timestamp.from(now));
            statement.executeUpdate();
        } catch (SQLException exception) {
            throw new RuntimeException("Failed to expire guest explorations", exception);
        }
    }

    private void insertExploration(
            Connection connection,
            ExplorationState state,
            String query,
            Instant createdAt) throws SQLException {
        Instant expiresAt = null;
        if (state.owner().type() == ExplorationActorType.GUEST) {
            expiresAt = createdAt.plus(GUEST_RETENTION);
        }
        var title = state.regionCode() != null ? state.regionCode() + " 여행" : "여정 탐색";
        var preview = query.length() > 160 ? query.substring(0, 160) : query;

        try (var statement = connection.prepareStatement("""
                INSERT INTO onmaru.discovery_explorations (
                    id, owner_member_id, owner_guest_id, state_version,
                    pinned_refs, excluded_refs, expires_at, created_at, updated_at,
                    title, last_user_query_preview
                ) VALUES (?, ?, ?, ?, '[]'::jsonb, '[]'::jsonb, ?, ?, ?, ?, ?)
                """)) {
            statement.setObject(1, state.id());
            if (state.owner().type() == ExplorationActorType.MEMBER) {
                statement.setObject(2, UUID.fromString(state.owner().subject()));
                statement.setNull(3, Types.OTHER);
            } else {
                statement.setNull(2, Types.OTHER);
                statement.setObject(3, UUID.fromString(state.owner().subject()));
            }
            statement.setInt(4, state.stateVersion());
            if (expiresAt != null) {
                statement.setTimestamp(5, Timestamp.from(expiresAt));
            } else {
                statement.setNull(5, Types.TIMESTAMP);
            }
            statement.setTimestamp(6, Timestamp.from(createdAt));
            statement.setTimestamp(7, Timestamp.from(state.updatedAt()));
            statement.setString(8, title);
            statement.setString(9, preview);
            statement.executeUpdate();
        }
    }

    private void insertRun(
            Connection connection,
            UUID explorationId,
            ExplorationActor actor,
            ExplorationRun run) throws SQLException {
        try (var statement = connection.prepareStatement("""
                INSERT INTO onmaru.discovery_runs (
                    id, exploration_id, actor_key, base_version, status, stage, outcome,
                    created_at, deadline_at, started_at, generation, engine
                ) VALUES (?, ?, ?, 0, ?::onmaru.discovery_run_status, ?, ?, ?, ?, ?, 1, ?)
                ON CONFLICT (id) DO NOTHING
                """)) {
            statement.setObject(1, run.id());
            statement.setObject(2, explorationId);
            statement.setString(3, actor.type().name().toLowerCase() + ":" + actor.subject());
            statement.setString(4, run.status().name());
            statement.setString(5, run.stage());
            statement.setString(6, run.outcome() != null ? run.outcome().name() : null);
            statement.setTimestamp(7, Timestamp.from(run.createdAt()));
            statement.setTimestamp(8, Timestamp.from(run.deadlineAt()));
            if (run.startedAt() != null) {
                statement.setTimestamp(9, Timestamp.from(run.startedAt()));
            } else {
                statement.setNull(9, Types.TIMESTAMP);
            }
            statement.setString(10, run.engine() != null ? run.engine() : "BASELINE");
            statement.executeUpdate();
        }
    }

    private void insertTurn(
            Connection connection,
            UUID explorationId,
            StoredExplorationTurn turn) throws SQLException {
        try (var statement = connection.prepareStatement("""
                INSERT INTO onmaru.discovery_turns (
                    id, exploration_id, client_turn_id, query, base_version, region_code,
                    clarification_id, run_id, created_at
                ) VALUES (gen_random_uuid(), ?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            statement.setObject(1, explorationId);
            statement.setObject(2, turn.clientTurnId());
            statement.setString(3, turn.query());
            statement.setInt(4, turn.baseVersion());
            statement.setString(5, turn.regionCode());
            statement.setString(6, turn.clarificationId());
            if (turn.run() != null) {
                statement.setObject(7, turn.run().id());
            } else {
                statement.setNull(7, Types.OTHER);
            }
            statement.setTimestamp(8, Timestamp.from(turn.createdAt()));
            statement.executeUpdate();
        }
    }

    private ExplorationActor findOwner(Connection connection, UUID explorationId) throws SQLException {
        try (var statement = connection.prepareStatement("""
                SELECT owner_member_id, owner_guest_id
                FROM onmaru.discovery_explorations
                WHERE id = ?
                """)) {
            statement.setObject(1, explorationId);
            try (var rs = statement.executeQuery()) {
                if (rs.next()) {
                    var memberId = (UUID) rs.getObject("owner_member_id");
                    if (memberId != null) {
                        return ExplorationActor.member(memberId);
                    }
                    var guestId = (UUID) rs.getObject("owner_guest_id");
                    return ExplorationActor.guest(guestId.toString());
                }
            }
        }
        throw new ExplorationNotFoundException();
    }

    private void cancelActiveRuns(Connection connection, UUID explorationId) throws SQLException {
        try (var statement = connection.prepareStatement("""
                UPDATE onmaru.discovery_runs
                SET status = 'CANCELLED', stage = NULL
                WHERE exploration_id = ? AND status IN ('QUEUED', 'RUNNING')
                """)) {
            statement.setObject(1, explorationId);
            statement.executeUpdate();
        }
    }

    private long countExplorations(Connection connection, ExplorationActor actor, Instant now) throws SQLException {
        String sql = """
                SELECT COUNT(*)
                FROM onmaru.discovery_explorations
                WHERE deleted_at IS NULL
                  AND (expires_at IS NULL OR expires_at > ?)
                  AND (%s = ?)
                """.formatted(actor.type() == ExplorationActorType.MEMBER ? "owner_member_id" : "owner_guest_id");
        try (var statement = connection.prepareStatement(sql)) {
            statement.setTimestamp(1, Timestamp.from(now));
            statement.setObject(2, UUID.fromString(actor.subject()));
            try (var rs = statement.executeQuery()) {
                if (rs.next()) {
                    return rs.getLong(1);
                }
            }
        }
        return 0;
    }

    private Optional<ExplorationRun> findRun(UUID explorationId, UUID runId) {
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement("""
                     SELECT id, status, engine, stage, outcome, created_at, started_at, deadline_at
                     FROM onmaru.discovery_runs
                     WHERE id = ? AND exploration_id = ?
                     """)) {
            statement.setObject(1, runId);
            statement.setObject(2, explorationId);
            try (var rs = statement.executeQuery()) {
                if (rs.next()) {
                    var status = ExplorationRunStatus.valueOf(rs.getString("status"));
                    var outcomeStr = rs.getString("outcome");
                    var outcome = outcomeStr != null ? ExplorationRunOutcome.valueOf(outcomeStr) : null;
                    return Optional.of(new ExplorationRun(
                            runId,
                            status,
                            rs.getString("engine"),
                            rs.getString("stage"),
                            outcome,
                            null,
                            rs.getTimestamp("created_at").toInstant(),
                            rs.getTimestamp("started_at") != null ? rs.getTimestamp("started_at").toInstant() : null,
                            rs.getTimestamp("deadline_at").toInstant()
                    ));
                }
            }
        } catch (SQLException exception) {
            throw new RuntimeException("Failed to find run", exception);
        }
        return Optional.empty();
    }

    private ExplorationState mapState(ResultSet rs) throws SQLException {
        var id = (UUID) rs.getObject("id");
        var memberId = (UUID) rs.getObject("owner_member_id");
        var guestId = (UUID) rs.getObject("owner_guest_id");
        var actor = memberId != null ? ExplorationActor.member(memberId) : ExplorationActor.guest(guestId.toString());
        var stateVersion = rs.getInt("state_version");
        var updatedAt = rs.getTimestamp("updated_at").toInstant();

        ExplorationRun run = null;
        var runId = (UUID) rs.getObject("run_id");
        if (runId != null) {
            var status = ExplorationRunStatus.valueOf(rs.getString("run_status"));
            var outcomeStr = rs.getString("run_outcome");
            var outcome = outcomeStr != null ? ExplorationRunOutcome.valueOf(outcomeStr) : null;
            run = new ExplorationRun(
                    runId,
                    status,
                    rs.getString("run_engine"),
                    rs.getString("run_stage"),
                    outcome,
                    null,
                    rs.getTimestamp("run_created_at").toInstant(),
                    rs.getTimestamp("run_started_at") != null ? rs.getTimestamp("run_started_at").toInstant() : null,
                    rs.getTimestamp("run_deadline_at").toInstant()
            );
        }

        return new ExplorationState(id, actor, stateVersion, null, run, updatedAt);
    }

    private StoredExplorationTurn mapTurn(ResultSet rs) throws SQLException {
        var clientTurnId = (UUID) rs.getObject("client_turn_id");
        var baseVersion = rs.getInt("base_version");
        var query = rs.getString("query");
        var regionCode = rs.getString("region_code");
        var clarificationId = rs.getString("clarification_id");
        var createdAt = rs.getTimestamp("created_at").toInstant();

        ExplorationRun run = null;
        var runId = (UUID) rs.getObject("run_id");
        if (runId != null) {
            var status = ExplorationRunStatus.valueOf(rs.getString("run_status"));
            var outcomeStr = rs.getString("run_outcome");
            var outcome = outcomeStr != null ? ExplorationRunOutcome.valueOf(outcomeStr) : null;
            run = new ExplorationRun(
                    runId,
                    status,
                    rs.getString("run_engine"),
                    rs.getString("run_stage"),
                    outcome,
                    null,
                    rs.getTimestamp("run_created_at").toInstant(),
                    rs.getTimestamp("run_started_at") != null ? rs.getTimestamp("run_started_at").toInstant() : null,
                    rs.getTimestamp("run_deadline_at").toInstant()
            );
        }

        return new StoredExplorationTurn(clientTurnId, baseVersion, query, regionCode, clarificationId, run, createdAt);
    }

    @SuppressWarnings("unchecked")
    private TurnResponse parseTurnResponse(String json, String runStatus, String runOutcome) {
        if (json != null && !json.isBlank()) {
            try {
                return objectMapper.readValue(json, TurnResponse.class);
            } catch (JsonProcessingException ignored) {
            }
        }
        if ("COMPLETED".equals(runStatus)) {
            return TurnResponse.completed(runOutcome, null, null, null, null);
        }
        if ("FAILED".equals(runStatus)) {
            return TurnResponse.failed(Map.of("code", "INTERNAL_ERROR"));
        }
        if ("CANCELLED".equals(runStatus)) {
            return TurnResponse.cancelled();
        }
        return TurnResponse.pending();
    }

    private static String encodeCursor(Instant instant) {
        return Base64.getUrlEncoder().encodeToString(instant.toString().getBytes());
    }

    private static Instant decodeCursor(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return null;
        }
        try {
            var decoded = new String(Base64.getUrlDecoder().decode(cursor));
            return Instant.parse(decoded);
        } catch (Exception exception) {
            return null;
        }
    }
}
