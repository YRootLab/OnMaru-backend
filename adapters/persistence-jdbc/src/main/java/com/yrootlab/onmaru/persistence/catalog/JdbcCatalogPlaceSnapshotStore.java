package com.yrootlab.onmaru.persistence.catalog;

import com.yrootlab.onmaru.catalog.application.query.hanok.HanokListCategory;
import com.yrootlab.onmaru.catalog.application.query.hanok.HanokListProjection;
import com.yrootlab.onmaru.catalog.application.query.hanok.HanokListStatus;
import com.yrootlab.onmaru.catalog.application.query.spatial.MapCoordinates;
import com.yrootlab.onmaru.catalog.application.query.spatial.MapCoverageStatus;
import com.yrootlab.onmaru.catalog.application.query.spatial.MapDataAvailability;
import com.yrootlab.onmaru.catalog.application.query.spatial.MapPlaceProjection;
import com.yrootlab.onmaru.catalog.application.query.spatial.MapPlaceStatus;
import com.yrootlab.onmaru.catalog.application.query.spatial.MapRegionRef;

import javax.sql.DataSource;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

public final class JdbcCatalogPlaceSnapshotStore {

    private static final String DATASET = "kto-korean-tour";
    private static final String SQL = """
            SELECT public_id.public_id, version.name, version.category, version.address,
                   region.code AS region_code, region.name AS region_name,
                   parent.code AS parent_region_code,
                   ST_X(version.location::geometry) AS longitude,
                   ST_Y(version.location::geometry) AS latitude,
                   image.origin_img_url AS thumbnail_url,
                   version.overview, revision.published_at, version.status,
                   ARRAY(SELECT tag.label
                         FROM onmaru.catalog_place_content_tag_versions tag
                         WHERE tag.revision_id = version.revision_id
                           AND tag.place_id = version.place_id
                         ORDER BY tag.position) AS tags
            FROM onmaru.catalog_active_datasets active
            JOIN onmaru.catalog_dataset_revisions revision
              ON revision.id = active.revision_id AND revision.status = 'PUBLISHED'
            JOIN onmaru.catalog_place_versions version ON version.revision_id = active.revision_id
            JOIN onmaru.catalog_place_public_ids public_id ON public_id.place_id = version.place_id
            LEFT JOIN onmaru.catalog_regions region ON region.id = version.region_id
            LEFT JOIN onmaru.catalog_regions parent ON parent.id = region.parent_id
            LEFT JOIN onmaru.catalog_place_image_versions image
              ON image.revision_id = version.revision_id
             AND image.place_id = version.place_id AND image.position = 0
            WHERE active.dataset = ?
            ORDER BY public_id.public_id
            """;

    private final DataSource dataSource;

    public JdbcCatalogPlaceSnapshotStore(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource);
    }

    public List<MapPlaceProjection> findPublishedMapSnapshot() {
        return read(this::mapPlace);
    }

    public List<HanokListProjection> findPublishedHanokSnapshot() {
        return read(this::hanok);
    }

    private <T> List<T> read(RowMapper<T> mapper) {
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement(SQL)) {
            statement.setString(1, DATASET);
            try (var rows = statement.executeQuery()) {
                var result = new ArrayList<T>();
                while (rows.next()) result.add(mapper.map(rows));
                return List.copyOf(result);
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("failed to read active TourAPI catalog snapshot", exception);
        }
    }

    private MapPlaceProjection mapPlace(ResultSet row) throws SQLException {
        Double lng = nullableDouble(row, "longitude");
        Double lat = nullableDouble(row, "latitude");
        String regionName = firstNonBlank(row.getString("region_name"), row.getString("address"));
        return new MapPlaceProjection(
                row.getString("public_id"), row.getString("name"), row.getString("category"),
                new MapRegionRef(row.getString("region_code"), regionName,
                        row.getString("region_code") == null ? null : "SIGUNGU",
                        row.getString("parent_region_code")),
                lng == null || lat == null ? null : new MapCoordinates(lat, lng),
                row.getString("thumbnail_url"), row.getString("overview"), List.of(),
                new MapDataAvailability(MapCoverageStatus.COMPLETE, MapCoverageStatus.MISSING, MapCoverageStatus.MISSING),
                "ACTIVE".equals(row.getString("status")) ? MapPlaceStatus.PUBLIC : MapPlaceStatus.HIDDEN);
    }

    private HanokListProjection hanok(ResultSet row) throws SQLException {
        return new HanokListProjection(
                row.getString("public_id"), row.getString("name"),
                HanokListCategory.valueOf(row.getString("category")), row.getString("region_code"),
                firstNonBlank(row.getString("region_name"), row.getString("address")),
                row.getString("thumbnail_url"), row.getString("overview"), tags(row.getArray("tags")),
                row.getObject("published_at", java.time.OffsetDateTime.class).toInstant(),
                "ACTIVE".equals(row.getString("status")) ? HanokListStatus.PUBLIC : HanokListStatus.HIDDEN);
    }

    private Double nullableDouble(ResultSet row, String name) throws SQLException {
        double value = row.getDouble(name);
        return row.wasNull() ? null : value;
    }

    private List<String> tags(Array array) throws SQLException {
        if (array == null) return List.of();
        try {
            return Arrays.stream((Object[]) array.getArray()).map(String::valueOf).toList();
        } finally {
            array.free();
        }
    }

    private String firstNonBlank(String first, String second) {
        return first != null && !first.isBlank() ? first : second;
    }

    @FunctionalInterface
    private interface RowMapper<T> {
        T map(ResultSet row) throws SQLException;
    }
}
