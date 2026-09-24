package com.yrootlab.onmaru.persistence.saved;

import com.yrootlab.onmaru.journey.saved.list.SavedResourceRecord;
import com.yrootlab.onmaru.journey.saved.list.SavedResourceRecordSource;
import com.yrootlab.onmaru.journey.saved.odii.SavedOdiiStoryLimitExceededException;
import com.yrootlab.onmaru.journey.saved.odii.SavedOdiiStoryState;
import com.yrootlab.onmaru.journey.saved.odii.SavedOdiiStoryStore;
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

/** {@code journey_saved_odii_stories} 기반 오디오 스토리 찜 영속 스토어. */
public final class JdbcSavedOdiiStoryStore implements SavedOdiiStoryStore {

    private final DataSource dataSource;

    public JdbcSavedOdiiStoryStore(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    @Override
    public SavedOdiiStoryState save(UUID memberId, String storyId, Instant savedAt, int limit) {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                var existing = findSavedAt(connection, memberId, storyId);
                if (existing.isPresent()) {
                    connection.commit();
                    return new SavedOdiiStoryState(
                            "1.2", SavedResourceType.ODII_STORY, storyId, storyId, true,
                            existing.get());
                }
                if (countFor(connection, memberId) >= limit) {
                    connection.rollback();
                    throw new SavedOdiiStoryLimitExceededException(limit);
                }
                try (PreparedStatement statement = connection.prepareStatement("""
                        INSERT INTO onmaru.journey_saved_odii_stories (id, member_id, story_id, saved_at)
                        VALUES (?, ?, ?, ?)
                        """)) {
                    statement.setObject(1, UUID.randomUUID());
                    statement.setObject(2, memberId);
                    statement.setString(3, storyId);
                    statement.setObject(4, savedAt);
                    statement.executeUpdate();
                }
                connection.commit();
                return new SavedOdiiStoryState(
                        "1.2", SavedResourceType.ODII_STORY, storyId, storyId, true, savedAt);
            } catch (SQLException exception) {
                connection.rollback();
                throw exception;
            } finally {
                connection.setAutoCommit(true);
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed to persist saved odii story: " + storyId, exception);
        }
    }

    @Override
    public void delete(UUID memberId, String storyId) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     DELETE FROM onmaru.journey_saved_odii_stories
                     WHERE member_id = ? AND story_id = ?
                     """)) {
            statement.setObject(1, memberId);
            statement.setString(2, storyId);
            statement.executeUpdate();
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed to delete saved odii story: " + storyId, exception);
        }
    }

    @Override
    public boolean savedBy(UUID memberId, String storyId) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT 1 FROM onmaru.journey_saved_odii_stories
                     WHERE member_id = ? AND story_id = ?
                     """)) {
            statement.setObject(1, memberId);
            statement.setString(2, storyId);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next();
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed to check saved odii story: " + storyId, exception);
        }
    }

    @Override
    public List<SavedResourceRecord> records(UUID memberId, SavedResourceType resourceType) {
        if (resourceType != SavedResourceType.ODII_STORY) {
            return List.of();
        }
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT id, story_id, saved_at FROM onmaru.journey_saved_odii_stories
                     WHERE member_id = ?
                     ORDER BY saved_at DESC, id DESC
                     """)) {
            statement.setObject(1, memberId);
            try (ResultSet resultSet = statement.executeQuery()) {
                var records = new ArrayList<SavedResourceRecord>();
                while (resultSet.next()) {
                    records.add(new SavedResourceRecord(
                            resultSet.getObject("id", UUID.class),
                            SavedResourceType.ODII_STORY,
                            resultSet.getString("story_id"),
                            toInstant(resultSet.getObject("saved_at", java.time.OffsetDateTime.class))));
                }
                return List.copyOf(records);
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed to list saved odii stories", exception);
        }
    }

    private long countFor(Connection connection, UUID memberId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT count(*) FROM onmaru.journey_saved_odii_stories WHERE member_id = ?
                """)) {
            statement.setObject(1, memberId);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? resultSet.getLong(1) : 0;
            }
        }
    }

    private java.util.Optional<Instant> findSavedAt(Connection connection, UUID memberId, String storyId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT saved_at FROM onmaru.journey_saved_odii_stories
                WHERE member_id = ? AND story_id = ?
                """)) {
            statement.setObject(1, memberId);
            statement.setString(2, storyId);
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
