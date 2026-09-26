package com.yrootlab.onmaru.persistence.community;

import com.yrootlab.onmaru.community.command.review.VisitReviewPlace;
import com.yrootlab.onmaru.community.command.review.VisitReviewPlaceLookup;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.util.Optional;

/** Resolves only active, review-eligible Catalog places for new VisitReview commands. */
public final class JdbcVisitReviewPlaceLookup implements VisitReviewPlaceLookup {

    private final DataSource dataSource;

    public JdbcVisitReviewPlaceLookup(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public Optional<VisitReviewPlace> findEligiblePlace(String publicPlaceId) {
        if (publicPlaceId == null || publicPlaceId.isBlank()) {
            return Optional.empty();
        }
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement("""
                     SELECT mapping.public_id, version.name, region.code,
                            ST_Y(version.location::geometry) AS latitude,
                            ST_X(version.location::geometry) AS longitude
                     FROM onmaru.catalog_place_public_ids mapping
                     JOIN onmaru.catalog_place_versions version ON version.place_id = mapping.place_id
                     JOIN onmaru.catalog_active_datasets active ON active.revision_id = version.revision_id
                     JOIN onmaru.catalog_regions region ON region.id = version.region_id
                     WHERE mapping.public_id = ?
                       AND version.status = 'ACTIVE'
                       AND version.visit_review_eligible = true
                       AND version.location IS NOT NULL
                     """)) {
            statement.setString(1, publicPlaceId);
            try (var result = statement.executeQuery()) {
                if (!result.next()) {
                    return Optional.empty();
                }
                return Optional.of(new VisitReviewPlace(
                        result.getString("public_id"),
                        result.getString("name"),
                        result.getString("code"),
                        result.getDouble("latitude"),
                        result.getDouble("longitude")));
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("VisitReview Catalog place lookup failed", exception);
        }
    }
}
