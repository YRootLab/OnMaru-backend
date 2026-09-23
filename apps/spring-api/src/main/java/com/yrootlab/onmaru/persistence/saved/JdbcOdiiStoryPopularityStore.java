package com.yrootlab.onmaru.persistence.saved;

import com.yrootlab.onmaru.audio.query.OdiiStoryPopularityPort;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * {@code audio_story_play_events}와 {@code journey_saved_odii_stories}를 집계하는
 * 인기 신호 JDBC 구현. 저장 수는 찜 테이블이 원천이므로 recordSave는 무시한다.
 */
public final class JdbcOdiiStoryPopularityStore implements OdiiStoryPopularityPort {

    private final DataSource dataSource;

    public JdbcOdiiStoryPopularityStore(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    @Override
    public void recordPlay(String storyId, Instant occurredAt) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     INSERT INTO onmaru.audio_story_play_events (id, story_id, occurred_at)
                     VALUES (?, ?, ?)
                     """)) {
            statement.setObject(1, UUID.randomUUID());
            statement.setString(2, storyId);
            statement.setObject(3, occurredAt);
            statement.executeUpdate();
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed to record play event: " + storyId, exception);
        }
    }

    @Override
    public void recordSave(String storyId, Instant occurredAt) {
        // 저장 수는 journey_saved_odii_stories 테이블이 원천이므로 별도 기록이 필요 없다.
    }

    @Override
    public Map<String, Long> playCounts(Collection<String> storyIds, Instant sinceInclusive) {
        return aggregate("onmaru.audio_story_play_events", "occurred_at", storyIds, sinceInclusive);
    }

    @Override
    public Map<String, Long> saveCounts(Collection<String> storyIds, Instant sinceInclusive) {
        return aggregate("onmaru.journey_saved_odii_stories", "saved_at", storyIds, sinceInclusive);
    }

    private Map<String, Long> aggregate(
            String table,
            String timeColumn,
            Collection<String> storyIds,
            Instant sinceInclusive) {
        if (storyIds.isEmpty()) {
            return Map.of();
        }
        var placeholders = String.join(", ", java.util.Collections.nCopies(storyIds.size(), "?"));
        var sql = "SELECT story_id, count(*) AS event_count FROM " + table
                + " WHERE story_id IN (" + placeholders + ") AND " + timeColumn + " >= ?"
                + " GROUP BY story_id";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            int index = 1;
            for (String storyId : storyIds) {
                statement.setString(index++, storyId);
            }
            statement.setObject(index, sinceInclusive);
            try (ResultSet resultSet = statement.executeQuery()) {
                var counts = new LinkedHashMap<String, Long>();
                while (resultSet.next()) {
                    counts.put(resultSet.getString("story_id"), resultSet.getLong("event_count"));
                }
                return counts;
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed to aggregate popularity for " + table, exception);
        }
    }
}
