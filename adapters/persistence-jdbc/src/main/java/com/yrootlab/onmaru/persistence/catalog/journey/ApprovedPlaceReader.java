package com.yrootlab.onmaru.persistence.catalog.journey;

import com.yrootlab.onmaru.catalog.application.query.journey.ApprovedPlaceLookupItem;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads ACTIVE place versions of the currently active TourAPI revision. The external place ID is the public
 * ID (p-...), never the internal identity UUID.
 */
final class ApprovedPlaceReader {

    static final String DATASET = "kto-korean-tour";

    private static final String SQL = """
            SELECT public_id.public_id AS id,
                   version.name,
                   version.category,
                   version.address,
                   region.code AS region_code,
                   ST_Y(version.location::geometry) AS latitude,
                   ST_X(version.location::geometry) AS longitude
            FROM onmaru.catalog_active_datasets active
            JOIN onmaru.catalog_place_versions version ON version.revision_id = active.revision_id
            JOIN onmaru.catalog_place_public_ids public_id ON public_id.place_id = version.place_id
            LEFT JOIN onmaru.catalog_regions region ON region.id = version.region_id
            WHERE active.dataset = ?
              AND version.status = 'ACTIVE'
              AND (? IS NULL OR region.code = ?)
              AND (? IS NULL OR version.category = ?)
            ORDER BY version.name, public_id.public_id
            LIMIT ?
            """;

    private static final String POOL_SQL_HEAD = """
            SELECT public_id.public_id AS id,
                   version.name,
                   version.category,
                   version.address,
                   region.code AS region_code,
                   ST_Y(version.location::geometry) AS latitude,
                   ST_X(version.location::geometry) AS longitude
            FROM onmaru.catalog_active_datasets active
            JOIN onmaru.catalog_place_versions version ON version.revision_id = active.revision_id
            JOIN onmaru.catalog_place_public_ids public_id ON public_id.place_id = version.place_id
            LEFT JOIN onmaru.catalog_regions region ON region.id = version.region_id
            WHERE active.dataset = ?
              AND version.status = 'ACTIVE'
            """;

    /**
     * Nationwide candidate pool for relevance ranking. Rows match when any query term appears in the name, address,
     * or category; with no usable terms the pool is the nationwide list in stable order. Region is never a filter here.
     */
    static List<ApprovedPlaceLookupItem> readPool(DataSource dataSource, List<String> tokens, int limit) {
        var matched = queryPool(dataSource, tokens, limit);
        if (!matched.isEmpty() || tokens.isEmpty()) {
            return matched;
        }
        // No place matches any query term: fall back to the nationwide list so ranking still has candidates.
        return queryPool(dataSource, List.of(), limit);
    }

    private static List<ApprovedPlaceLookupItem> queryPool(DataSource dataSource, List<String> tokens, int limit) {
        var sql = new StringBuilder(POOL_SQL_HEAD);
        var params = new ArrayList<Object>();
        params.add(DATASET);
        if (!tokens.isEmpty()) {
            var clauses = new ArrayList<String>();
            for (String token : tokens) {
                var like = "%" + token + "%";
                clauses.add("version.name ILIKE ?");
                clauses.add("version.address ILIKE ?");
                clauses.add("version.category ILIKE ?");
                params.add(like);
                params.add(like);
                params.add(like);
                if (JourneyRelevanceRanker.isHanokTheme(token)) {
                    clauses.add("version.category ILIKE ?");
                    params.add("HANOK%");
                }
            }
            sql.append("  AND (").append(String.join(" OR ", clauses)).append(")\n");
        }
        sql.append("ORDER BY version.name, public_id.public_id\nLIMIT ?\n");
        params.add(limit);
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement(sql.toString())) {
            for (int index = 0; index < params.size(); index++) {
                statement.setObject(index + 1, params.get(index));
            }
            return collect(statement);
        } catch (SQLException exception) {
            throw new IllegalStateException("approved place pool could not be read", exception);
        }
    }

    private static List<ApprovedPlaceLookupItem> collect(java.sql.PreparedStatement statement) throws SQLException {
        var items = new ArrayList<ApprovedPlaceLookupItem>();
        try (var rows = statement.executeQuery()) {
            while (rows.next()) {
                items.add(new ApprovedPlaceLookupItem(
                        rows.getString("id"),
                        rows.getString("name"),
                        rows.getString("category"),
                        rows.getString("address"),
                        rows.getString("region_code"),
                        (Double) rows.getObject("latitude"),
                        (Double) rows.getObject("longitude")));
            }
        }
        return List.copyOf(items);
    }

    private ApprovedPlaceReader() {
    }

    static List<ApprovedPlaceLookupItem> read(DataSource dataSource, String regionCode, String category, int limit) {
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement(SQL)) {
            statement.setString(1, DATASET);
            statement.setString(2, regionCode);
            statement.setString(3, regionCode);
            statement.setString(4, category);
            statement.setString(5, category);
            statement.setInt(6, limit);
            var items = new ArrayList<ApprovedPlaceLookupItem>();
            try (var rows = statement.executeQuery()) {
                while (rows.next()) {
                    items.add(new ApprovedPlaceLookupItem(
                            rows.getString("id"),
                            rows.getString("name"),
                            rows.getString("category"),
                            rows.getString("address"),
                            rows.getString("region_code"),
                            (Double) rows.getObject("latitude"),
                            (Double) rows.getObject("longitude")));
                }
            }
            return List.copyOf(items);
        } catch (SQLException exception) {
            throw new IllegalStateException("approved places could not be read", exception);
        }
    }
}
