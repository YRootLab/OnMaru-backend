package com.yrootlab.onmaru.persistence.catalog.journey;

import com.yrootlab.onmaru.catalog.application.query.journey.ApprovedPlaceLookupItem;
import com.yrootlab.onmaru.catalog.application.query.journey.PlaceQueryPort;

import javax.sql.DataSource;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

public final class JdbcPlaceQueryAdapter implements PlaceQueryPort {

    private final DataSource dataSource;

    public JdbcPlaceQueryAdapter(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public List<ApprovedPlaceLookupItem> lookupApprovedPlaces(String regionCode, String category, int limit) {
        int boundedLimit = Math.min(Math.max(limit, 1), 5);

        String sql = """
                SELECT p.id, p.name, p.category, p.road_address, r.code AS region_code,
                       ST_Y(p.coordinates::geometry) AS latitude,
                       ST_X(p.coordinates::geometry) AS longitude
                FROM onmaru.catalog_places p
                JOIN onmaru.catalog_regions r ON r.id = p.region_id
                WHERE p.status = 'PUBLISHED'
                  AND (? IS NULL OR r.code = ?)
                  AND (? IS NULL OR p.category = ?)
                ORDER BY p.name ASC
                LIMIT ?
                """;

        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement(sql)) {
            statement.setString(1, regionCode);
            statement.setString(2, regionCode);
            statement.setString(3, category);
            statement.setString(4, category);
            statement.setInt(5, boundedLimit);

            var items = new ArrayList<ApprovedPlaceLookupItem>();
            try (var rs = statement.executeQuery()) {
                while (rs.next()) {
                    items.add(new ApprovedPlaceLookupItem(
                            rs.getString("id"),
                            rs.getString("name"),
                            rs.getString("category"),
                            rs.getString("road_address"),
                            rs.getString("region_code"),
                            rs.getDouble("latitude"),
                            rs.getDouble("longitude")
                    ));
                }
            }
            return List.copyOf(items);
        } catch (SQLException exception) {
            throw new RuntimeException("Failed to lookup approved places", exception);
        }
    }
}
