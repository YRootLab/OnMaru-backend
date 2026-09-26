package com.yrootlab.onmaru.persistence.stamp;

import com.yrootlab.onmaru.persistence.jdbc.JdbcTransactionRunner;
import com.yrootlab.onmaru.stamp.ranking.StampRankingEntry;
import com.yrootlab.onmaru.stamp.ranking.StampRankingIdentity;
import com.yrootlab.onmaru.stamp.ranking.StampRankingIdentityConflictException;
import com.yrootlab.onmaru.stamp.ranking.StampRankingNicknameType;
import com.yrootlab.onmaru.stamp.ranking.StampRankingRateLimitedException;
import com.yrootlab.onmaru.stamp.ranking.StampRankingStatus;
import com.yrootlab.onmaru.stamp.ranking.StampRankingStore;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public final class JdbcStampRankingStore implements StampRankingStore {
    private static final String RANKED_SCORES = """
            WITH active_definitions AS (
                SELECT code, condition_type, region_group FROM onmaru.stamp_definitions WHERE active
            ), definition_total AS (
                SELECT count(*)::int AS total FROM active_definitions
            ), participant_scores AS (
                SELECT profile.member_id, profile.ranking_public_id, profile.public_nickname, profile.nickname_type,
                       count(definition.code)::int AS stamp_count,
                       count(DISTINCT definition.region_group)
                           FILTER (WHERE definition.condition_type = 'REGION_VISIT')::int AS visited_region_count,
                       max(award.awarded_at) FILTER (WHERE definition.code IS NOT NULL) AS last_awarded_at
                FROM onmaru.stamp_ranking_profiles profile
                JOIN onmaru.identity_members member ON member.id = profile.member_id AND member.status = 'ACTIVE'
                LEFT JOIN onmaru.stamp_awards award ON award.member_id = profile.member_id
                LEFT JOIN active_definitions definition ON definition.code = award.stamp_code
                WHERE profile.participating
                GROUP BY profile.member_id, profile.ranking_public_id, profile.public_nickname, profile.nickname_type
            ), ranked AS (
                SELECT row_number() OVER (
                           ORDER BY stamp_count DESC, visited_region_count DESC,
                                    last_awarded_at ASC NULLS LAST, ranking_public_id ASC
                       )::int AS rank, participant_scores.*,
                       CASE WHEN total = 0 THEN 0 ELSE stamp_count * 100 / total END AS completion_rate
                FROM participant_scores CROSS JOIN definition_total
            )
            """;
    private static final String LEADERBOARD = RANKED_SCORES + """
            SELECT rank, ranking_public_id, public_nickname, nickname_type,
                   stamp_count, visited_region_count, completion_rate
            FROM ranked ORDER BY rank LIMIT ?
            """;
    private static final String STATUS = RANKED_SCORES + """
            , personal_scores AS (
                SELECT count(definition.code)::int AS stamp_count,
                       count(DISTINCT definition.region_group)
                           FILTER (WHERE definition.condition_type = 'REGION_VISIT')::int AS visited_region_count
                FROM onmaru.stamp_awards award
                JOIN active_definitions definition ON definition.code = award.stamp_code
                WHERE award.member_id = ?
            )
            SELECT coalesce(profile.participating, false) AS participating,
                   profile.public_nickname, profile.nickname_type, ranked.rank,
                   (SELECT count(*)::int FROM ranked) AS participant_count,
                   personal_scores.stamp_count, personal_scores.visited_region_count,
                   CASE WHEN total = 0 THEN 0 ELSE personal_scores.stamp_count * 100 / total END AS completion_rate
            FROM personal_scores CROSS JOIN definition_total
            LEFT JOIN onmaru.stamp_ranking_profiles profile ON profile.member_id = ?
            LEFT JOIN ranked ON ranked.member_id = profile.member_id
            """;

    private final JdbcTransactionRunner transactions;

    public JdbcStampRankingStore(DataSource dataSource) {
        this(dataSource, new JdbcTransactionRunner(dataSource));
    }

    public JdbcStampRankingStore(DataSource dataSource, JdbcTransactionRunner transactions) {
        Objects.requireNonNull(dataSource, "dataSource");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
    }

    @Override
    public List<StampRankingEntry> leaderboard(int limit) {
        return transactions.execute(connection -> {
            try (var statement = connection.prepareStatement(LEADERBOARD)) {
                statement.setInt(1, limit);
                try (var result = statement.executeQuery()) {
                    var entries = new ArrayList<StampRankingEntry>();
                    while (result.next()) {
                        entries.add(new StampRankingEntry(
                                result.getInt("rank"), result.getObject("ranking_public_id", UUID.class),
                                result.getString("public_nickname"),
                                StampRankingNicknameType.valueOf(result.getString("nickname_type")),
                                result.getInt("stamp_count"), result.getInt("visited_region_count"),
                                result.getInt("completion_rate")));
                    }
                    return List.copyOf(entries);
                }
            } catch (SQLException exception) {
                throw new IllegalStateException("Failed to read stamp leaderboard", exception);
            }
        });
    }

    @Override
    public StampRankingStatus status(UUID memberId) {
        return transactions.execute(connection -> status(connection, memberId));
    }

    @Override
    public StampRankingStatus participate(UUID memberId, StampRankingIdentity identity, Instant now) {
        return transactions.execute(connection -> {
            try {
                lockMember(connection, memberId);
                requireActiveMember(connection, memberId);
                var current = profile(connection, memberId);
                if (current != null && current.participating()) {
                    return status(connection, memberId);
                }
                if (current != null && current.updatedAt().plusSeconds(5).isAfter(now)) {
                    var remaining = Duration.between(now, current.updatedAt().plusSeconds(5));
                    throw new StampRankingRateLimitedException(
                            remaining.getSeconds() + (remaining.getNano() > 0 ? 1 : 0));
                }
                upsertParticipation(connection, memberId, identity, now);
                return status(connection, memberId);
            } catch (SQLException exception) {
                throw new IllegalStateException("Failed to participate in stamp ranking", exception);
            }
        });
    }

    @Override
    public StampRankingStatus withdraw(UUID memberId, Instant now) {
        return transactions.execute(connection -> {
            try {
                lockMember(connection, memberId);
                requireActiveMember(connection, memberId);
                var current = profile(connection, memberId);
                if (current != null && current.participating()) {
                    try (var statement = connection.prepareStatement("""
                            UPDATE onmaru.stamp_ranking_profiles
                            SET participating = false, ranking_public_id = NULL, public_nickname = NULL,
                                nickname_normalized = NULL, nickname_type = NULL, withdrawn_at = ?, updated_at = ?
                            WHERE member_id = ?
                            """)) {
                        statement.setObject(1, now.atOffset(ZoneOffset.UTC));
                        statement.setObject(2, now.atOffset(ZoneOffset.UTC));
                        statement.setObject(3, memberId);
                        statement.executeUpdate();
                    }
                }
                return status(connection, memberId);
            } catch (SQLException exception) {
                throw new IllegalStateException("Failed to withdraw from stamp ranking", exception);
            }
        });
    }

    private StampRankingStatus status(Connection connection, UUID memberId) {
        try (var statement = connection.prepareStatement(STATUS)) {
            statement.setObject(1, memberId);
            statement.setObject(2, memberId);
            try (var result = statement.executeQuery()) {
                result.next();
                var nicknameType = result.getString("nickname_type");
                return new StampRankingStatus(
                        result.getBoolean("participating"), result.getString("public_nickname"),
                        nicknameType == null ? null : StampRankingNicknameType.valueOf(nicknameType),
                        result.getObject("rank", Integer.class), result.getInt("participant_count"),
                        result.getInt("stamp_count"), result.getInt("visited_region_count"),
                        result.getInt("completion_rate"));
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed to read stamp ranking status", exception);
        }
    }

    private void lockMember(Connection connection, UUID memberId) throws SQLException {
        try (var statement = connection.prepareStatement("SELECT pg_advisory_xact_lock(hashtext('stamp-ranking|' || ?))")) {
            statement.setString(1, memberId.toString());
            statement.execute();
        }
    }

    private void requireActiveMember(Connection connection, UUID memberId) throws SQLException {
        try (var statement = connection.prepareStatement("""
                SELECT status::text FROM onmaru.identity_members WHERE id = ? FOR UPDATE
                """)) {
            statement.setObject(1, memberId);
            try (var result = statement.executeQuery()) {
                if (!result.next() || !"ACTIVE".equals(result.getString(1))) {
                    throw new IllegalStateException("Inactive member cannot change stamp ranking participation");
                }
            }
        }
    }

    private Profile profile(Connection connection, UUID memberId) throws SQLException {
        try (var statement = connection.prepareStatement("""
                SELECT participating, updated_at FROM onmaru.stamp_ranking_profiles WHERE member_id = ?
                """)) {
            statement.setObject(1, memberId);
            try (var result = statement.executeQuery()) {
                return result.next() ? new Profile(result.getBoolean("participating"),
                        result.getObject("updated_at", OffsetDateTime.class).toInstant()) : null;
            }
        }
    }

    private void upsertParticipation(Connection connection, UUID memberId, StampRankingIdentity identity, Instant now)
            throws SQLException {
        // Keep an outer shared transaction usable when the service retries an identity collision.
        var savepoint = connection.setSavepoint();
        try (var statement = connection.prepareStatement("""
                INSERT INTO onmaru.stamp_ranking_profiles
                    (member_id, ranking_public_id, public_nickname, nickname_normalized, nickname_type,
                     participating, consented_at, withdrawn_at, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, true, ?, NULL, ?, ?)
                ON CONFLICT (member_id) DO UPDATE SET
                    ranking_public_id = EXCLUDED.ranking_public_id, public_nickname = EXCLUDED.public_nickname,
                    nickname_normalized = EXCLUDED.nickname_normalized, nickname_type = EXCLUDED.nickname_type,
                    participating = true, consented_at = EXCLUDED.consented_at, withdrawn_at = NULL,
                    updated_at = EXCLUDED.updated_at
                """)) {
            statement.setObject(1, memberId);
            statement.setObject(2, identity.publicId());
            statement.setString(3, identity.publicNickname());
            statement.setString(4, identity.nicknameNormalized());
            statement.setString(5, identity.nicknameType().name());
            statement.setObject(6, now.atOffset(ZoneOffset.UTC));
            statement.setObject(7, now.atOffset(ZoneOffset.UTC));
            statement.setObject(8, now.atOffset(ZoneOffset.UTC));
            statement.executeUpdate();
        } catch (SQLException exception) {
            connection.rollback(savepoint);
            if (isIdentityConflict(exception)) {
                throw new StampRankingIdentityConflictException();
            }
            throw exception;
        } finally {
            connection.releaseSavepoint(savepoint);
        }
    }

    private boolean isIdentityConflict(SQLException exception) {
        var message = exception.getMessage();
        return "23505".equals(exception.getSQLState()) && message != null
                && (message.contains("\"stamp_ranking_profiles_public_id_uq\"")
                    || message.contains("\"stamp_ranking_profiles_nickname_uq\""));
    }

    private record Profile(boolean participating, Instant updatedAt) { }
}
