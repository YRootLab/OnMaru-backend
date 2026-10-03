package com.yrootlab.onmaru.persistence.identity;

import com.yrootlab.onmaru.identity.lifecycle.MemberLifecycleStatus;
import com.yrootlab.onmaru.identity.lifecycle.MemberLifecycleStore;
import com.yrootlab.onmaru.identity.lifecycle.MemberSummary;
import com.yrootlab.onmaru.identity.oauth.ExternalIdentity;
import com.yrootlab.onmaru.identity.oauth.IdentityStore;
import com.yrootlab.onmaru.identity.oauth.OAuthStateRecord;
import com.yrootlab.onmaru.identity.oauth.SessionRecord;
import com.yrootlab.onmaru.identity.profile.MemberProfile;
import com.yrootlab.onmaru.identity.profile.MemberProfileBackground;
import com.yrootlab.onmaru.identity.profile.MemberProfileCharacter;
import com.yrootlab.onmaru.identity.profile.MemberProfileStore;
import com.yrootlab.onmaru.identity.profile.NewMemberProfile;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public final class JdbcIdentityStore implements IdentityStore, MemberLifecycleStore, MemberProfileStore {

    private final DataSource dataSource;

    public JdbcIdentityStore(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public void saveOAuthState(OAuthStateRecord state) {
        withConnection(connection -> {
            try (var statement = connection.prepareStatement("""
                    INSERT INTO onmaru.identity_oauth_states (
                        state_hash, guest_id, browser_nonce_hash, pkce_verifier_hash,
                        exploration_id, provider, return_path, expires_at, consumed_at
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """)) {
                statement.setString(1, state.stateHash());
                statement.setObject(2, state.guestId());
                statement.setString(3, state.browserNonceHash());
                statement.setString(4, state.pkceVerifierHash());
                statement.setObject(5, state.explorationId());
                statement.setString(6, state.provider());
                statement.setString(7, state.returnPath());
                statement.setObject(8, utc(state.expiresAt()));
                statement.setObject(9, state.consumedAt() == null ? null : utc(state.consumedAt()));
                statement.executeUpdate();
                return null;
            }
        });
    }

    @Override
    public Optional<OAuthStateRecord> consumeOAuthState(
            String stateHash,
            String provider,
            String browserNonceHash,
            String pkceVerifierHash,
            Instant now
    ) {
        return withConnection(connection -> {
            try (var statement = connection.prepareStatement("""
                    UPDATE onmaru.identity_oauth_states
                    SET consumed_at = ?
                    WHERE state_hash = ? AND provider = ? AND browser_nonce_hash = ?
                      AND pkce_verifier_hash = ? AND consumed_at IS NULL AND expires_at > ?
                    RETURNING guest_id, exploration_id, return_path, expires_at, consumed_at
                    """)) {
                statement.setObject(1, utc(now));
                statement.setString(2, stateHash);
                statement.setString(3, provider);
                statement.setString(4, browserNonceHash);
                statement.setString(5, pkceVerifierHash);
                statement.setObject(6, utc(now));
                try (var result = statement.executeQuery()) {
                    if (!result.next()) {
                        return Optional.empty();
                    }
                    return Optional.of(new OAuthStateRecord(
                            stateHash,
                            result.getObject("guest_id", UUID.class),
                            browserNonceHash,
                            pkceVerifierHash,
                            result.getObject("exploration_id", UUID.class),
                            provider,
                            result.getString("return_path"),
                            result.getObject("expires_at", OffsetDateTime.class).toInstant(),
                            result.getObject("consumed_at", OffsetDateTime.class).toInstant()));
                }
            }
        });
    }

    @Override
    public UUID linkExternalIdentity(ExternalIdentity identity, NewMemberProfile profile, Instant now) {
        return inTransaction(connection -> {
            try (var existing = connection.prepareStatement("""
                    SELECT member_id FROM onmaru.identity_external_accounts
                    WHERE provider = ? AND issuer = ? AND subject = ?
                    FOR UPDATE
                    """)) {
                bindIdentity(existing, identity);
                try (var result = existing.executeQuery()) {
                    if (result.next()) {
                        return result.getObject(1, UUID.class);
                    }
                }
            }

            var memberId = UUID.randomUUID();
            try (var member = connection.prepareStatement("""
                    INSERT INTO onmaru.identity_members (id, status, created_at) VALUES (?, 'ACTIVE', ?)
                    """)) {
                member.setObject(1, memberId);
                member.setObject(2, utc(now));
                member.executeUpdate();
            }
            try (var memberProfile = connection.prepareStatement("""
                    INSERT INTO onmaru.identity_member_profiles (
                        member_id, display_name, character_id, background_id, created_at, updated_at
                    ) VALUES (?, ?, ?, ?, ?, ?)
                    """)) {
                memberProfile.setObject(1, memberId);
                memberProfile.setString(2, profile.displayName());
                memberProfile.setString(3, profile.characterId().name());
                memberProfile.setString(4, profile.backgroundId().name());
                memberProfile.setObject(5, utc(now));
                memberProfile.setObject(6, utc(now));
                memberProfile.executeUpdate();
            }
            try (var account = connection.prepareStatement("""
                    INSERT INTO onmaru.identity_external_accounts (
                        id, member_id, provider, issuer, subject, created_at
                    ) VALUES (?, ?, ?, ?, ?, ?)
                    ON CONFLICT (provider, issuer, subject) DO NOTHING
                    RETURNING member_id
                    """)) {
                account.setObject(1, UUID.randomUUID());
                account.setObject(2, memberId);
                account.setString(3, identity.provider());
                account.setString(4, identity.issuer());
                account.setString(5, identity.subject());
                account.setObject(6, utc(now));
                try (var linked = account.executeQuery()) {
                    if (linked.next()) {
                        return linked.getObject(1, UUID.class);
                    }
                }
            }
            try (var orphan = connection.prepareStatement("DELETE FROM onmaru.identity_members WHERE id = ?")) {
                orphan.setObject(1, memberId);
                orphan.executeUpdate();
            }
            try (var winner = connection.prepareStatement("""
                    SELECT member_id FROM onmaru.identity_external_accounts
                    WHERE provider = ? AND issuer = ? AND subject = ?
                    """)) {
                bindIdentity(winner, identity);
                try (var result = winner.executeQuery()) {
                    if (result.next()) {
                        return result.getObject(1, UUID.class);
                    }
                }
            }
            throw new IllegalStateException("External identity link disappeared after conflict");
        });
    }

    @Override
    public void saveSession(SessionRecord session) {
        withConnection(connection -> {
            try (var statement = connection.prepareStatement("""
                    INSERT INTO onmaru.identity_sessions (
                        token_hash, member_id, created_at, last_seen_at, absolute_expires_at, revoked_at
                    )
                    SELECT ?, member.id, ?, ?, ?, NULL
                    FROM onmaru.identity_members member
                    WHERE member.id = ? AND member.status = 'ACTIVE'
                    """)) {
                statement.setString(1, session.tokenHash());
                statement.setObject(2, utc(session.createdAt()));
                statement.setObject(3, utc(session.lastSeenAt()));
                statement.setObject(4, utc(session.absoluteExpiresAt()));
                statement.setObject(5, session.memberId());
                if (statement.executeUpdate() != 1) {
                    throw new IllegalStateException("Inactive member cannot receive a session");
                }
                return null;
            }
        });
    }

    @Override
    public Optional<MemberSummary> findActiveMemberBySessionHash(String sessionTokenHash, Instant now) {
        return withConnection(connection -> {
            try (var statement = connection.prepareStatement("""
                    SELECT member.id, profile.display_name, profile.character_id, profile.background_id
                    FROM onmaru.identity_sessions session
                    JOIN onmaru.identity_members member ON member.id = session.member_id
                    JOIN onmaru.identity_member_profiles profile ON profile.member_id = member.id
                    WHERE session.token_hash = ? AND session.revoked_at IS NULL
                      AND session.absolute_expires_at > ? AND member.status = 'ACTIVE'
                    """)) {
                statement.setString(1, sessionTokenHash);
                statement.setObject(2, utc(now));
                try (var result = statement.executeQuery()) {
                    return result.next()
                            ? Optional.of(new MemberSummary(
                                    result.getObject("id", UUID.class),
                                    result.getString("display_name"),
                                    result.getString("character_id"),
                                    result.getString("background_id")))
                            : Optional.empty();
                }
            }
        });
    }

    @Override
    public boolean revokeSession(String sessionTokenHash, Instant now) {
        return withConnection(connection -> {
            try (var statement = connection.prepareStatement("""
                    UPDATE onmaru.identity_sessions SET revoked_at = ?
                    WHERE token_hash = ? AND revoked_at IS NULL
                    """)) {
                statement.setObject(1, utc(now));
                statement.setString(2, sessionTokenHash);
                return statement.executeUpdate() == 1;
            }
        });
    }

    @Override
    public int revokeAllSessions(UUID memberId, Instant now) {
        return withConnection(connection -> {
            try (var statement = connection.prepareStatement("""
                    UPDATE onmaru.identity_sessions SET revoked_at = ?
                    WHERE member_id = ? AND revoked_at IS NULL
                    """)) {
                statement.setObject(1, utc(now));
                statement.setObject(2, memberId);
                return statement.executeUpdate();
            }
        });
    }

    @Override
    public Optional<MemberLifecycleStatus> requestDeletion(String sessionTokenHash, Instant now) {
        return inTransaction(connection -> {
            UUID memberId;
            try (var statement = connection.prepareStatement("""
                    SELECT member.id
                    FROM onmaru.identity_sessions session
                    JOIN onmaru.identity_members member ON member.id = session.member_id
                    WHERE session.token_hash = ? AND session.revoked_at IS NULL
                      AND session.absolute_expires_at > ? AND member.status = 'ACTIVE'
                    FOR UPDATE OF session, member
                    """)) {
                statement.setString(1, sessionTokenHash);
                statement.setObject(2, utc(now));
                try (var result = statement.executeQuery()) {
                    if (!result.next()) {
                        return Optional.empty();
                    }
                    memberId = result.getObject(1, UUID.class);
                }
            }
            try (var member = connection.prepareStatement(
                    "UPDATE onmaru.identity_members SET status = 'DELETING' WHERE id = ?")) {
                member.setObject(1, memberId);
                member.executeUpdate();
            }
            try (var ledger = connection.prepareStatement("""
                    INSERT INTO onmaru.identity_deletion_ledger (member_id, requested_at, status, reason)
                    VALUES (?, ?, 'REQUESTED', 'USER_REQUESTED')
                    ON CONFLICT (member_id) DO NOTHING
                    """)) {
                ledger.setObject(1, memberId);
                ledger.setObject(2, utc(now));
                ledger.executeUpdate();
            }
            try (var sessions = connection.prepareStatement("""
                    UPDATE onmaru.identity_sessions SET revoked_at = ?
                    WHERE member_id = ? AND revoked_at IS NULL
                    """)) {
                sessions.setObject(1, utc(now));
                sessions.setObject(2, memberId);
                sessions.executeUpdate();
            }
            return Optional.of(MemberLifecycleStatus.DELETING);
        });
    }

    @Override
    public boolean allowsLateWrite(UUID memberId) {
        return withConnection(connection -> {
            try (var statement = connection.prepareStatement("""
                    SELECT EXISTS (
                        SELECT 1 FROM onmaru.identity_members member
                        WHERE member.id = ? AND member.status = 'ACTIVE'
                          AND NOT EXISTS (
                              SELECT 1 FROM onmaru.identity_deletion_ledger ledger
                              WHERE ledger.member_id = member.id
                          )
                    )
                    """)) {
                statement.setObject(1, memberId);
                try (var result = statement.executeQuery()) {
                    result.next();
                    return result.getBoolean(1);
                }
            }
        });
    }

    @Override
    public Optional<MemberProfile> findByMemberId(UUID memberId) {
        return withConnection(connection -> {
            try (var statement = connection.prepareStatement("""
                    SELECT member_id, display_name, character_id, background_id, created_at, updated_at
                    FROM onmaru.identity_member_profiles
                    WHERE member_id = ?
                    """)) {
                statement.setObject(1, memberId);
                try (var result = statement.executeQuery()) {
                    return result.next() ? Optional.of(mapProfile(result)) : Optional.empty();
                }
            }
        });
    }

    @Override
    public Map<UUID, MemberProfile> findByMemberIds(Set<UUID> memberIds) {
        if (memberIds.isEmpty()) {
            return Map.of();
        }
        return withConnection(connection -> {
            try (var statement = connection.prepareStatement("""
                    SELECT member_id, display_name, character_id, background_id, created_at, updated_at
                    FROM onmaru.identity_member_profiles
                    WHERE member_id = ANY (?)
                    """)) {
                statement.setArray(1, connection.createArrayOf("uuid", memberIds.toArray()));
                try (var result = statement.executeQuery()) {
                    var profiles = new HashMap<UUID, MemberProfile>();
                    while (result.next()) {
                        var profile = mapProfile(result);
                        profiles.put(profile.memberId(), profile);
                    }
                    return Map.copyOf(profiles);
                }
            }
        });
    }

    @Override
    public Optional<MemberProfile> updateActiveProfile(
            UUID memberId,
            String displayName,
            MemberProfileCharacter characterId,
            MemberProfileBackground backgroundId,
            Instant updatedAt) {
        return withConnection(connection -> {
            try (var statement = connection.prepareStatement("""
                    UPDATE onmaru.identity_member_profiles profile
                    SET display_name = ?, character_id = ?, background_id = ?, updated_at = ?
                    FROM onmaru.identity_members member
                    WHERE profile.member_id = ? AND member.id = profile.member_id
                      AND member.status = 'ACTIVE'
                    RETURNING profile.member_id, profile.display_name, profile.character_id,
                              profile.background_id, profile.created_at, profile.updated_at
                    """)) {
                statement.setString(1, displayName);
                statement.setString(2, characterId.name());
                statement.setString(3, backgroundId.name());
                statement.setObject(4, utc(updatedAt));
                statement.setObject(5, memberId);
                try (var result = statement.executeQuery()) {
                    return result.next() ? Optional.of(mapProfile(result)) : Optional.empty();
                }
            }
        });
    }

    private MemberProfile mapProfile(java.sql.ResultSet result) throws SQLException {
        return new MemberProfile(
                result.getObject("member_id", UUID.class),
                result.getString("display_name"),
                MemberProfileCharacter.valueOf(result.getString("character_id")),
                MemberProfileBackground.valueOf(result.getString("background_id")),
                result.getObject("created_at", OffsetDateTime.class).toInstant(),
                result.getObject("updated_at", OffsetDateTime.class).toInstant());
    }

    private void bindIdentity(java.sql.PreparedStatement statement, ExternalIdentity identity) throws SQLException {
        statement.setString(1, identity.provider());
        statement.setString(2, identity.issuer());
        statement.setString(3, identity.subject());
    }

    private <T> T withConnection(SqlOperation<T> operation) {
        try (var connection = dataSource.getConnection()) {
            return operation.apply(connection);
        } catch (SQLException exception) {
            throw new IllegalStateException("Identity persistence failed", exception);
        }
    }

    private <T> T inTransaction(SqlOperation<T> operation) {
        try (var connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                var result = operation.apply(connection);
                connection.commit();
                return result;
            } catch (RuntimeException | SQLException exception) {
                connection.rollback();
                if (exception instanceof RuntimeException runtime) {
                    throw runtime;
                }
                throw (SQLException) exception;
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Identity transaction failed", exception);
        }
    }

    private OffsetDateTime utc(Instant value) {
        return OffsetDateTime.ofInstant(value, ZoneOffset.UTC);
    }

    @FunctionalInterface
    private interface SqlOperation<T> {
        T apply(Connection connection) throws SQLException;
    }
}
