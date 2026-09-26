package com.yrootlab.onmaru.persistence.stamp;

import com.yrootlab.onmaru.persistence.jdbc.JdbcTransactionRunner;
import com.yrootlab.onmaru.stamp.CheckInPlaceLookup;
import com.yrootlab.onmaru.stamp.VerifiedPlace;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.util.Objects;
import java.util.Optional;

public final class JdbcCheckInPlaceLookup implements CheckInPlaceLookup {

    private final JdbcTransactionRunner transactions;

    public JdbcCheckInPlaceLookup(DataSource dataSource) {
        this(dataSource, new JdbcTransactionRunner(dataSource));
    }

    public JdbcCheckInPlaceLookup(DataSource dataSource, JdbcTransactionRunner transactions) {
        Objects.requireNonNull(dataSource, "dataSource");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
    }

    @Override
    public Optional<VerifiedPlace> verify(String placeId, double latitude, double longitude) {
        if (placeId == null || placeId.isBlank()) {
            return Optional.empty();
        }
        return transactions.execute(connection -> {
            try (var statement = connection.prepareStatement("""
                    SELECT mapping.place_id,
                           mapping.public_id,
                           region.code AS region_code,
                           ceil(ST_Distance(
                               version.location,
                               ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography
                           ))::integer AS distance_meters
                    FROM onmaru.catalog_place_public_ids mapping
                    JOIN onmaru.catalog_place_versions version ON version.place_id = mapping.place_id
                    JOIN onmaru.catalog_active_datasets active ON active.revision_id = version.revision_id
                    JOIN onmaru.catalog_regions region ON region.id = version.region_id
                    WHERE mapping.public_id = ?
                      AND version.status = 'ACTIVE'
                      AND version.category IN ('HANOK', 'HANOK_STAY', 'HANOK_CAFE', 'HANOK_EXPERIENCE')
                      AND version.location IS NOT NULL
                    ORDER BY active.activated_at DESC
                    LIMIT 1
                    """)) {
                statement.setDouble(1, longitude);
                statement.setDouble(2, latitude);
                statement.setString(3, placeId);
                try (var result = statement.executeQuery()) {
                    if (!result.next()) {
                        return Optional.empty();
                    }
                    return Optional.of(new VerifiedPlace(
                            result.getObject("place_id", java.util.UUID.class),
                            result.getString("public_id"),
                            result.getString("region_code"),
                            result.getInt("distance_meters")));
                }
            } catch (SQLException exception) {
                throw new IllegalStateException("Stamp Catalog place lookup failed", exception);
            }
        });
    }
}
