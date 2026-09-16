package com.yrootlab.onmaru.persistence.audio;

import com.yrootlab.onmaru.audio.placelink.AudioPlaceLinkCandidate;
import com.yrootlab.onmaru.audio.placelink.AudioPlaceLinkCandidateNotFoundException;
import com.yrootlab.onmaru.audio.placelink.AudioPlaceLinkMatchMethod;
import com.yrootlab.onmaru.audio.placelink.AudioPlaceLinkReviewStatus;
import com.yrootlab.onmaru.audio.placelink.AudioPlaceLinkStore;
import com.yrootlab.onmaru.observability.TelemetryEvent;
import com.yrootlab.onmaru.observability.TelemetrySink;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public final class JdbcAudioPlaceLinkStore implements AudioPlaceLinkStore {

    private static final String SPOT_PREFIX = "odii-spot-";

    private final DataSource dataSource;
    private final TelemetrySink telemetrySink;

    public JdbcAudioPlaceLinkStore(DataSource dataSource) {
        this(dataSource, ignored -> {
        });
    }

    public JdbcAudioPlaceLinkStore(DataSource dataSource, TelemetrySink telemetrySink) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.telemetrySink = Objects.requireNonNull(telemetrySink, "telemetrySink");
    }

    @Override
    public AudioPlaceLinkCandidate saveIfAbsent(AudioPlaceLinkCandidate candidate) {
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement("""
                     INSERT INTO onmaru.audio_place_odii_links (
                         place_id, spot_id, match_method, confidence, review_status, verified_at
                     ) VALUES (?, ?, ?, ?, ?::varchar, ?)
                     ON CONFLICT (place_id, spot_id) DO NOTHING
                     """)) {
            bindCandidate(connection, statement, candidate);
            statement.executeUpdate();
            return find(candidate.spotId(), candidate.placeId()).orElseThrow(AudioPlaceLinkCandidateNotFoundException::new);
        } catch (SQLException exception) {
            throw new IllegalStateException("failed to save audio place link candidate", exception);
        }
    }

    @Override
    public void save(AudioPlaceLinkCandidate candidate) {
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement("""
                     INSERT INTO onmaru.audio_place_odii_links (
                         place_id, spot_id, match_method, confidence, review_status, verified_at
                     ) VALUES (?, ?, ?, ?, ?::varchar, ?)
                     ON CONFLICT (place_id, spot_id) DO UPDATE
                     SET match_method = EXCLUDED.match_method,
                         confidence = EXCLUDED.confidence,
                         review_status = EXCLUDED.review_status,
                         verified_at = EXCLUDED.verified_at
                     """)) {
            bindCandidate(connection, statement, candidate);
            statement.executeUpdate();
        } catch (SQLException exception) {
            throw new IllegalStateException("failed to save audio place link candidate", exception);
        }
    }

    @Override
    public Optional<AudioPlaceLinkCandidate> find(String spotId, String placeId) {
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement("""
                     SELECT link.place_id, link.spot_id, link.match_method, link.confidence,
                            link.review_status, link.verified_at, spot.provider, spot.tid
                     FROM onmaru.audio_place_odii_links link
                     JOIN onmaru.audio_odii_spots spot ON spot.id = link.spot_id
                     WHERE link.spot_id = ?
                       AND link.place_id = ?
                     """)) {
            statement.setObject(1, resolveSpotId(connection, spotId));
            statement.setObject(2, toUuid(placeId, ""));
            try (var resultSet = statement.executeQuery()) {
                return resultSet.next() ? Optional.of(readCandidate(resultSet)) : Optional.empty();
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("failed to read audio place link candidate", exception);
        }
    }

    @Override
    public List<AudioPlaceLinkCandidate> findBySpotId(String spotId) {
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement("""
                     SELECT link.place_id, link.spot_id, link.match_method, link.confidence,
                            link.review_status, link.verified_at, spot.provider, spot.tid
                     FROM onmaru.audio_place_odii_links link
                     JOIN onmaru.audio_odii_spots spot ON spot.id = link.spot_id
                     WHERE link.spot_id = ?
                     ORDER BY link.verified_at NULLS FIRST, link.place_id
                     """)) {
            statement.setObject(1, resolveSpotId(connection, spotId));
            try (var resultSet = statement.executeQuery()) {
                List<AudioPlaceLinkCandidate> candidates = new ArrayList<>();
                while (resultSet.next()) {
                    candidates.add(readCandidate(resultSet));
                }
                return candidates;
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("failed to list audio place link candidates", exception);
        }
    }

    @Override
    public AudioPlaceLinkCandidate approveExclusive(String spotId, String placeId, Instant reviewedAt) {
        try (var connection = dataSource.getConnection()) {
            var originalAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                UUID storedSpotId = resolveSpotId(connection, spotId);
                UUID storedPlaceId = toUuid(placeId, "");
                lockPendingCandidateSet(connection, storedSpotId, storedPlaceId);
                try (var rejectOthers = connection.prepareStatement("""
                        UPDATE onmaru.audio_place_odii_links
                        SET review_status = 'REJECTED',
                            verified_at = ?
                        WHERE spot_id = ?
                          AND place_id <> ?
                        """)) {
                    rejectOthers.setTimestamp(1, Timestamp.from(reviewedAt));
                    rejectOthers.setObject(2, storedSpotId);
                    rejectOthers.setObject(3, storedPlaceId);
                    rejectOthers.executeUpdate();
                }
                AudioPlaceLinkCandidate approved = updateStatus(
                        connection,
                        spotId,
                        placeId,
                        storedSpotId,
                        storedPlaceId,
                        AudioPlaceLinkReviewStatus.APPROVED,
                        reviewedAt);
                connection.commit();
                recordApproval("odii.place_link.approval.completed", spotId, placeId, "APPROVED");
                return approved;
            } catch (SQLException | RuntimeException exception) {
                connection.rollback();
                recordApproval(
                        "odii.place_link.approval.failed",
                        spotId,
                        placeId,
                        exception instanceof AudioPlaceLinkCandidateNotFoundException ? "CONFLICT" : "ROLLBACK");
                throw exception;
            } finally {
                connection.setAutoCommit(originalAutoCommit);
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("failed to approve audio place link candidate", exception);
        }
    }

    @Override
    public AudioPlaceLinkCandidate reject(String spotId, String placeId, Instant reviewedAt) {
        try (var connection = dataSource.getConnection()) {
            return updateStatus(
                    connection,
                    spotId,
                    placeId,
                    resolveSpotId(connection, spotId),
                    toUuid(placeId, ""),
                    AudioPlaceLinkReviewStatus.REJECTED,
                    reviewedAt);
        } catch (SQLException exception) {
            throw new IllegalStateException("failed to reject audio place link candidate", exception);
        }
    }

    private void lockPendingCandidateSet(
            Connection connection,
            UUID spotId,
            UUID expectedPlaceId
    ) throws SQLException {
        boolean pendingTargetFound = false;
        try (var statement = connection.prepareStatement("""
                SELECT place_id, review_status
                FROM onmaru.audio_place_odii_links
                WHERE spot_id = ?
                ORDER BY place_id
                FOR UPDATE
                """)) {
            statement.setObject(1, spotId);
            try (var resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    if (expectedPlaceId.equals(resultSet.getObject("place_id", UUID.class))
                            && AudioPlaceLinkReviewStatus.PENDING.name()
                            .equals(resultSet.getString("review_status"))) {
                        pendingTargetFound = true;
                    }
                }
            }
        }
        if (!pendingTargetFound) {
            throw new AudioPlaceLinkCandidateNotFoundException();
        }
    }

    private AudioPlaceLinkCandidate updateStatus(
            Connection connection,
            String publicSpotId,
            String publicPlaceId,
            UUID spotId,
            UUID placeId,
            AudioPlaceLinkReviewStatus status,
            Instant reviewedAt) throws SQLException {
        try (var statement = connection.prepareStatement("""
                UPDATE onmaru.audio_place_odii_links
                SET review_status = ?,
                    verified_at = ?
                WHERE spot_id = ?
                  AND place_id = ?
                """)) {
            statement.setString(1, status.name());
            statement.setTimestamp(2, Timestamp.from(reviewedAt));
            statement.setObject(3, spotId);
            statement.setObject(4, placeId);
            if (statement.executeUpdate() != 1) {
                throw new AudioPlaceLinkCandidateNotFoundException();
            }
            return find(connection, publicSpotId, publicPlaceId)
                    .orElseThrow(AudioPlaceLinkCandidateNotFoundException::new);
        }
    }

    private void bindCandidate(
            Connection connection,
            java.sql.PreparedStatement statement,
            AudioPlaceLinkCandidate candidate)
            throws SQLException {
        statement.setObject(1, toUuid(candidate.placeId(), ""));
        statement.setObject(2, resolveSpotId(connection, candidate.spotId()));
        statement.setString(3, candidate.matchMethod().name());
        statement.setBigDecimal(4, candidate.confidence());
        statement.setString(5, candidate.reviewStatus().name());
        if (candidate.reviewedAt() == null) {
            statement.setTimestamp(6, null);
        } else {
            statement.setTimestamp(6, Timestamp.from(candidate.reviewedAt()));
        }
    }

    private AudioPlaceLinkCandidate readCandidate(ResultSet resultSet) throws SQLException {
        UUID placeId = resultSet.getObject("place_id", UUID.class);
        Timestamp verifiedAt = resultSet.getTimestamp("verified_at");
        return new AudioPlaceLinkCandidate(
                publicSpotId(resultSet.getString("provider"), resultSet.getString("tid")),
                placeId.toString(),
                AudioPlaceLinkMatchMethod.valueOf(resultSet.getString("match_method")),
                resultSet.getBigDecimal("confidence"),
                AudioPlaceLinkReviewStatus.valueOf(resultSet.getString("review_status")),
                verifiedAt == null ? null : verifiedAt.toInstant());
    }

    private Optional<AudioPlaceLinkCandidate> find(
            Connection connection,
            String spotId,
            String placeId
    ) throws SQLException {
        try (var statement = connection.prepareStatement("""
                SELECT link.place_id, link.spot_id, link.match_method, link.confidence,
                       link.review_status, link.verified_at, spot.provider, spot.tid
                FROM onmaru.audio_place_odii_links link
                JOIN onmaru.audio_odii_spots spot ON spot.id = link.spot_id
                WHERE link.spot_id = ? AND link.place_id = ?
                """)) {
            statement.setObject(1, resolveSpotId(connection, spotId));
            statement.setObject(2, toUuid(placeId, ""));
            try (var result = statement.executeQuery()) {
                return result.next() ? Optional.of(readCandidate(result)) : Optional.empty();
            }
        }
    }

    private UUID resolveSpotId(Connection connection, String publicSpotId) throws SQLException {
        try (var statement = connection.prepareStatement("""
                SELECT id, provider, tid
                FROM onmaru.audio_odii_spots
                ORDER BY CASE WHEN lang_code = 'ko' THEN 0 ELSE 1 END, id
                """)) {
            try (var result = statement.executeQuery()) {
                while (result.next()) {
                    if (publicSpotId(result.getString("provider"), result.getString("tid"))
                            .equals(publicSpotId)) {
                        return result.getObject("id", UUID.class);
                    }
                }
            }
        }
        UUID legacyId = toUuid(publicSpotId, SPOT_PREFIX);
        try (var statement = connection.prepareStatement("""
                SELECT id FROM onmaru.audio_odii_spots WHERE id = ?
                """)) {
            statement.setObject(1, legacyId);
            try (var result = statement.executeQuery()) {
                if (result.next()) {
                    return legacyId;
                }
            }
        }
        throw new AudioPlaceLinkCandidateNotFoundException();
    }

    private String publicSpotId(String provider, String tid) {
        String stableKey = provider + ":" + SPOT_PREFIX + ":" + tid;
        return SPOT_PREFIX + UUID.nameUUIDFromBytes(stableKey.getBytes(StandardCharsets.UTF_8));
    }

    private void recordApproval(String name, String spotId, String placeId, String status) {
        telemetrySink.record(new TelemetryEvent(name, Map.of(
                "spotId", spotId,
                "placeId", placeId,
                "status", status
        )));
    }

    private UUID toUuid(String value, String prefix) {
        String raw = value.startsWith(prefix) ? value.substring(prefix.length()) : value;
        return UUID.fromString(raw);
    }
}
