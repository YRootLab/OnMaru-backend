package com.yrootlab.onmaru.persistence.community;

import com.yrootlab.onmaru.catalog.publicid.CatalogPublicPlaceIdStore;
import com.yrootlab.onmaru.community.query.MutableVisitReviewStore;
import com.yrootlab.onmaru.community.query.VisitReviewProjection;
import com.yrootlab.onmaru.community.query.VisitReviewStatus;
import com.yrootlab.onmaru.persistence.jdbc.JdbcTransactionRunner;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.sql.Types;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

/** PostgreSQL VisitReview store preserving the write-time public-place snapshot. */
public final class JdbcVisitReviewStore implements MutableVisitReviewStore {

    private final DataSource dataSource;
    private final CatalogPublicPlaceIdStore publicPlaceIds;
    private final JdbcTransactionRunner transactions;

    public JdbcVisitReviewStore(DataSource dataSource, CatalogPublicPlaceIdStore publicPlaceIds) {
        this(dataSource, publicPlaceIds, new JdbcTransactionRunner(dataSource));
    }

    public JdbcVisitReviewStore(
            DataSource dataSource,
            CatalogPublicPlaceIdStore publicPlaceIds,
            JdbcTransactionRunner transactions) {
        this.dataSource = dataSource;
        this.publicPlaceIds = publicPlaceIds;
        this.transactions = transactions;
    }

    @Override
    public void add(VisitReviewProjection review) {
        var catalogPlaceId = publicPlaceIds.findPlaceId(review.placeId())
                .orElseThrow(() -> new IllegalArgumentException("VisitReview placeId is not registered by Catalog"));
        transactions.execute(connection -> {
            try {
                insertReview(connection, review, catalogPlaceId);
                replaceLikes(connection, review.id(), review.likedMemberIds());
            } catch (Exception exception) {
                throw databaseFailure(exception);
            }
            return null;
        });
    }

    @Override
    public List<VisitReviewProjection> findSnapshot() {
        return transactions.execute(connection -> findSnapshot(connection));
    }

    private List<VisitReviewProjection> findSnapshot(java.sql.Connection connection) {
        try (var statement = connection.prepareStatement("""
                     SELECT review.id, review.member_id, review.public_place_id, review.place_name,
                            review.region_code, review.latitude, review.longitude, review.text,
                            review.mood, review.score, review.tags, review.created_at, review.status,
                            COALESCE(array_agg(like_row.member_id) FILTER (WHERE like_row.member_id IS NOT NULL),
                                     ARRAY[]::uuid[]) AS liked_member_ids
                     FROM onmaru.community_visit_reviews review
                     LEFT JOIN onmaru.community_review_likes like_row ON like_row.review_id = review.id
                     WHERE review.public_place_id IS NOT NULL
                     GROUP BY review.id, review.member_id, review.public_place_id, review.place_name,
                              review.region_code, review.latitude, review.longitude, review.text,
                              review.mood, review.score, review.tags, review.created_at, review.status
                     ORDER BY review.created_at DESC, review.id DESC
                     """);
             var result = statement.executeQuery()) {
            var reviews = new ArrayList<VisitReviewProjection>();
            while (result.next()) {
                Number latitudeValue = (Number) result.getObject("latitude");
                Number longitudeValue = (Number) result.getObject("longitude");
                if (latitudeValue == null || longitudeValue == null) {
                    continue;
                }
                double latitude = latitudeValue.doubleValue();
                double longitude = longitudeValue.doubleValue();
                reviews.add(new VisitReviewProjection(
                        result.getObject("id", UUID.class),
                        result.getString("public_place_id"),
                        result.getString("place_name"),
                        result.getString("region_code"),
                        latitude,
                        longitude,
                        result.getString("text"),
                        result.getString("mood"),
                        result.getObject("score", Integer.class),
                        tags(result.getString("tags")),
                        result.getObject("created_at", OffsetDateTime.class).toInstant(),
                        result.getObject("member_id", UUID.class),
                        uuidSet(result.getArray("liked_member_ids").getArray()),
                        VisitReviewStatus.valueOf(result.getString("status"))));
            }
            return List.copyOf(reviews);
        } catch (Exception exception) {
            throw databaseFailure(exception);
        }
    }

    @Override
    public void remove(UUID reviewId) {
        transactions.execute(connection -> {
            try (var delete = connection.prepareStatement("DELETE FROM onmaru.community_visit_reviews WHERE id = ?")) {
                delete.setObject(1, reviewId);
                delete.executeUpdate();
                return null;
            } catch (SQLException exception) { throw databaseFailure(exception); }
        });
    }

    @Override
    public void replace(VisitReviewProjection review) {
        var catalogPlaceId = publicPlaceIds.findPlaceId(review.placeId())
                .orElseThrow(() -> new IllegalArgumentException("VisitReview placeId is not registered by Catalog"));
        transactions.execute(connection -> {
            try {
                try (var update = connection.prepareStatement("""
                        UPDATE onmaru.community_visit_reviews
                        SET member_id = ?, place_id = ?, text = ?, mood = ?, score = ?, tags = ?::jsonb,
                            status = ?::onmaru.community_review_status, created_at = ?,
                            deleted_at = CASE WHEN ?::onmaru.community_review_status = 'REMOVED' THEN now() ELSE NULL END,
                            public_place_id = ?, place_name = ?, region_code = ?, latitude = ?, longitude = ?
                        WHERE id = ?
                        """)) {
                    update.setObject(1, review.authorMemberId());
                    update.setObject(2, catalogPlaceId);
                    update.setString(3, review.text());
                    update.setString(4, review.mood());
                    if (review.score() == null) {
                        update.setNull(5, Types.SMALLINT);
                    } else {
                        update.setInt(5, review.score());
                    }
                    update.setString(6, tagsJson(review.tags()));
                    update.setString(7, review.status().name());
                    update.setObject(8, OffsetDateTime.ofInstant(review.createdAt(), ZoneOffset.UTC));
                    update.setString(9, review.status().name());
                    update.setString(10, review.placeId());
                    update.setString(11, review.placeName());
                    update.setString(12, review.regionCode());
                    update.setDouble(13, review.lat());
                    update.setDouble(14, review.lng());
                    update.setObject(15, review.id());
                    if (update.executeUpdate() != 1) {
                        throw new IllegalArgumentException("VisitReview does not exist");
                    }
                }
                replaceLikes(connection, review.id(), review.likedMemberIds());
                return null;
            } catch (Exception exception) {
                throw databaseFailure(exception);
            }
        });
    }

    @Override
    public Optional<VisitReviewProjection> update(
            UUID reviewId,
            Function<VisitReviewProjection, VisitReviewProjection> updater) {
        return transactions.execute(ignored -> {
            lockReview(reviewId);
            var current = findSnapshot().stream().filter(review -> review.id().equals(reviewId)).findFirst();
            if (current.isEmpty()) return Optional.empty();
            var updated = updater.apply(current.get());
            replace(updated);
            return Optional.of(updated);
        });
    }

    private void lockReview(UUID reviewId) {
        transactions.execute(connection -> {
            try (var statement = connection.prepareStatement(
                    "SELECT id FROM onmaru.community_visit_reviews WHERE id = ? FOR UPDATE")) {
                statement.setObject(1, reviewId);
                statement.executeQuery();
                return null;
            } catch (SQLException exception) { throw databaseFailure(exception); }
        });
    }

    private void insertReview(java.sql.Connection connection, VisitReviewProjection review, UUID catalogPlaceId) throws SQLException {
        try (var insert = connection.prepareStatement("""
                INSERT INTO onmaru.community_visit_reviews (
                    id, member_id, place_id, text, mood, score, tags, status, created_at, deleted_at,
                    public_place_id, place_name, region_code, latitude, longitude
                ) VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?::onmaru.community_review_status, ?, NULL, ?, ?, ?, ?, ?)
                """)) {
            insert.setObject(1, review.id());
            bindReview(insert, review, catalogPlaceId, 2);
            insert.executeUpdate();
        }
    }

    private void bindReview(java.sql.PreparedStatement statement, VisitReviewProjection review, UUID catalogPlaceId) throws SQLException {
        bindReview(statement, review, catalogPlaceId, 1);
    }

    private void bindReview(
            java.sql.PreparedStatement statement,
            VisitReviewProjection review,
            UUID catalogPlaceId,
            int offset) throws SQLException {
        statement.setObject(offset, review.authorMemberId());
        statement.setObject(offset + 1, catalogPlaceId);
        statement.setString(offset + 2, review.text());
        statement.setString(offset + 3, review.mood());
        if (review.score() == null) {
            statement.setNull(offset + 4, Types.SMALLINT);
        } else {
            statement.setInt(offset + 4, review.score());
        }
        statement.setString(offset + 5, tagsJson(review.tags()));
        statement.setString(offset + 6, review.status().name());
        statement.setObject(offset + 7, OffsetDateTime.ofInstant(review.createdAt(), ZoneOffset.UTC));
        statement.setString(offset + 8, review.placeId());
        statement.setString(offset + 9, review.placeName());
        statement.setString(offset + 10, review.regionCode());
        statement.setDouble(offset + 11, review.lat());
        statement.setDouble(offset + 12, review.lng());
    }

    private void replaceLikes(java.sql.Connection connection, UUID reviewId, Set<UUID> memberIds) throws SQLException {
        try (var delete = connection.prepareStatement("DELETE FROM onmaru.community_review_likes WHERE review_id = ?")) {
            delete.setObject(1, reviewId);
            delete.executeUpdate();
        }
        try (var insert = connection.prepareStatement("""
                INSERT INTO onmaru.community_review_likes (review_id, member_id, created_at)
                VALUES (?, ?, now())
                """)) {
            for (var memberId : memberIds) {
                insert.setObject(1, reviewId);
                insert.setObject(2, memberId);
                insert.addBatch();
            }
            insert.executeBatch();
        }
    }

    private Set<UUID> uuidSet(Object values) {
        var ids = new LinkedHashSet<UUID>();
        for (var value : (Object[]) values) {
            ids.add(value instanceof UUID uuid ? uuid : UUID.fromString(value.toString()));
        }
        return Set.copyOf(ids);
    }

    private String tagsJson(List<String> tags) {
        return tags.stream()
                .map(tag -> "\"" + tag.replace("\\", "\\\\").replace("\"", "\\\"") + "\"")
                .collect(java.util.stream.Collectors.joining(",", "[", "]"));
    }

    private List<String> tags(String json) {
        if (json == null || json.equals("[]")) {
            return List.of();
        }
        var values = new ArrayList<String>();
        var index = 1;
        while (index < json.length() - 1) {
            while (Character.isWhitespace(json.charAt(index)) || json.charAt(index) == ',') {
                index++;
            }
            if (json.charAt(index++) != '\"') {
                throw new IllegalStateException("VisitReview tags must contain JSON strings");
            }
            var value = new StringBuilder();
            while (json.charAt(index) != '\"') {
                var character = json.charAt(index++);
                if (character == '\\') {
                    character = json.charAt(index++);
                }
                value.append(character);
            }
            index++;
            values.add(value.toString());
        }
        return List.copyOf(values);
    }

    private IllegalStateException databaseFailure(Exception exception) {
        return new IllegalStateException("VisitReview database operation failed", exception);
    }
}
