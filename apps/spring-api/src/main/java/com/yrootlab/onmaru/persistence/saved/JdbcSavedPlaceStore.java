package com.yrootlab.onmaru.persistence.saved;

import com.yrootlab.onmaru.journey.saved.list.SavedResourceRecord;
import com.yrootlab.onmaru.journey.saved.list.SavedPlaceRecordSource;
import com.yrootlab.onmaru.journey.saved.place.SavedPlaceLimitExceededException;
import com.yrootlab.onmaru.journey.saved.place.SavedPlaceState;
import com.yrootlab.onmaru.journey.saved.place.SavedPlaceStore;
import com.yrootlab.onmaru.journey.saved.place.SavedResourceType;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** {@code journey_saved_places} 기반 장소 찜 영속 스토어. */
public final class JdbcSavedPlaceStore implements SavedPlaceStore, SavedPlaceRecordSource {

    private final DataSource dataSource;

    public JdbcSavedPlaceStore(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    @Override
    public SavedPlaceState save(UUID memberId, String placeId, Instant savedAt, int limit) {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                var existing = findSavedAt(connection, memberId, placeId);
                if (existing.isPresent()) {
                    connection.commit();
                    return new SavedPlaceState(
                            "1.2", SavedResourceType.PLACE, placeId, placeId, true,
                            existing.get());
                }
                if (countFor(connection, memberId) >= limit) {
                    connection.rollback();
                    throw new SavedPlaceLimitExceededException(limit);
                }
                try (PreparedStatement statement = connection.prepareStatement("""
                        INSERT INTO onmaru.journey_saved_places (id, member_id, place_id, saved_at)
                        VALUES (?, ?, ?, ?)
                        """)) {
                    statement.setObject(1, UUID.randomUUID());
                    statement.setObject(2, memberId);
                    statement.setString(3, placeId);
                    statement.setObject(4, savedAt);
                    statement.executeUpdate();
                }
                connection.commit();
                return new SavedPlaceState(
                        "1.2", SavedResourceType.PLACE, placeId, placeId, true, savedAt);
            } catch (SQLException exception) {
                connection.rollback();
                throw exception;
            } finally {
                connection.setAutoCommit(true);
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed to persist saved place: " + placeId, exception);
        }
    }

    @Override
    public void delete(UUID memberId, String placeId) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     DELETE FROM onmaru.journey_saved_places
                     WHERE member_id = ? AND place_id = ?
                     """)) {
            statement.setObject(1, memberId);
            statement.setString(2, placeId);
            statement.executeUpdate();
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed to delete saved place: " + placeId, exception);
        }
    }

    @Override
    public boolean savedBy(UUID memberId, String placeId) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT 1 FROM onmaru.journey_saved_places
                     WHERE member_id = ? AND place_id = ?
                     """)) {
            statement.setObject(1, memberId);
            statement.setString(2, placeId);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next();
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed to check saved place: " + placeId, exception);
        }
    }

    @Override
    public long countFor(UUID memberId, SavedResourceType resourceType) {
        if (resourceType != SavedResourceType.PLACE) {
            return 0;
        }
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT count(*) FROM onmaru.journey_saved_places WHERE member_id = ?
                     """)) {
            statement.setObject(1, memberId);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? resultSet.getLong(1) : 0;
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed to count saved places", exception);
        }
    }

    @Override
    public List<SavedResourceRecord> records(UUID memberId, SavedResourceType resourceType) {
        if (resourceType != SavedResourceType.PLACE) {
            return List.of();
        }
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT id, place_id, saved_at FROM onmaru.journey_saved_places
                     WHERE member_id = ?
                     ORDER BY saved_at DESC, id DESC
                     """)) {
            statement.setObject(1, memberId);
            try (ResultSet resultSet = statement.executeQuery()) {
                var records = new ArrayList<SavedResourceRecord>();
                while (resultSet.next()) {
                    records.add(new SavedResourceRecord(
                            resultSet.getObject("id", UUID.class),
                            SavedResourceType.PLACE,
                            resultSet.getString("place_id"),
                            toInstant(resultSet.getObject("saved_at", java.time.OffsetDateTime.class))));
                }
                return List.copyOf(records);
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed to list saved places", exception);
        }
    }

    private long countFor(Connection connection, UUID memberId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT count(*) FROM onmaru.journey_saved_places WHERE member_id = ?
                """)) {
            statement.setObject(1, memberId);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? resultSet.getLong(1) : 0;
            }
        }
    }

    private java.util.Optional<Instant> findSavedAt(Connection connection, UUID memberId, String placeId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT saved_at FROM onmaru.journey_saved_places
                WHERE member_id = ? AND place_id = ?
                """)) {
            statement.setObject(1, memberId);
            statement.setString(2, placeId);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return java.util.Optional.empty();
                }
                return java.util.Optional.of(
                        toInstant(resultSet.getObject("saved_at", java.time.OffsetDateTime.class)));
            }
        }
    }

    private static Instant toInstant(java.time.OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }
}
