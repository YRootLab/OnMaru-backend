package com.yrootlab.onmaru.persistence.catalog.journey;

import com.yrootlab.onmaru.catalog.application.query.journey.ApprovedPlaceLookupItem;

import javax.sql.DataSource;
import java.sql.Array;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Reads ACTIVE place versions of the currently active PUBLISHED TourAPI revision. The external place ID is the
 * public ID (p-...), never the internal identity UUID.
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
            JOIN onmaru.catalog_dataset_revisions revision
              ON revision.id = active.revision_id AND revision.status = 'PUBLISHED'
            JOIN onmaru.catalog_place_versions version ON version.revision_id = active.revision_id
            JOIN onmaru.catalog_place_public_ids public_id ON public_id.place_id = version.place_id
            LEFT JOIN onmaru.catalog_regions region ON region.id = version.region_id
            WHERE active.dataset = ?
              AND version.status = 'ACTIVE'
              AND (? IS NULL OR region.code = ?)
              AND (? IS NULL OR version.category = ?)
              AND (NOT ? OR public_id.public_id = ANY(?))
              AND (NOT ? OR NOT (public_id.public_id = ANY(?)))
            ORDER BY version.name, public_id.public_id
            LIMIT ?
            """;

    private ApprovedPlaceReader() {
    }

    static List<ApprovedPlaceLookupItem> read(DataSource dataSource, String regionCode, String category, int limit) {
        return read(dataSource, regionCode, category, null, null, limit);
    }

    static List<ApprovedPlaceLookupItem> read(
            DataSource dataSource,
            String regionCode,
            String category,
            List<String> allowedPlaceIds,
            List<String> excludedPlaceIds,
            int limit) {
        if (limit <= 0) {
            return List.of();
        }
        String[] allowed = null;
        if (allowedPlaceIds != null) {
            allowed = allowedPlaceIds.stream()
                    .filter(Objects::nonNull)
                    .filter(id -> !id.isBlank())
                    .toArray(String[]::new);
            if (allowed.length == 0) {
                return List.of();
            }
        }
        String[] excluded = excludedPlaceIds == null
                ? new String[0]
                : excludedPlaceIds.stream()
                        .filter(Objects::nonNull)
                        .filter(id -> !id.isBlank())
                        .toArray(String[]::new);

        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement(SQL)) {
            Array allowedArray = connection.createArrayOf("text", allowed != null ? allowed : new String[0]);
            Array excludedArray = connection.createArrayOf("text", excluded);
            try {
                statement.setString(1, DATASET);
                statement.setString(2, regionCode);
                statement.setString(3, regionCode);
                statement.setString(4, category);
                statement.setString(5, category);
                statement.setBoolean(6, allowed != null);
                statement.setArray(7, allowedArray);
                statement.setBoolean(8, excluded.length > 0);
                statement.setArray(9, excludedArray);
                statement.setInt(10, limit);
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
            } finally {
                allowedArray.free();
                excludedArray.free();
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("approved places could not be read", exception);
        }
    }
}
