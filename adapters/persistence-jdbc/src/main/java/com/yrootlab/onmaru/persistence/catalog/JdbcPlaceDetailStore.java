package com.yrootlab.onmaru.persistence.catalog;

import com.yrootlab.onmaru.catalog.application.query.detail.CoordinatesProjection;
import com.yrootlab.onmaru.catalog.application.query.detail.ImageProjection;
import com.yrootlab.onmaru.catalog.application.query.detail.PlaceDetailStore;
import com.yrootlab.onmaru.catalog.application.query.detail.PlaceProjection;
import com.yrootlab.onmaru.catalog.application.query.detail.PlaceProjectionStatus;
import com.yrootlab.onmaru.catalog.application.query.detail.RegionProjection;

import javax.sql.DataSource;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public final class JdbcPlaceDetailStore implements PlaceDetailStore {

    private static final String SQL = """
            SELECT public_id.public_id, version.name, version.category, version.address,
                   version.overview, version.status,
                   region.code AS region_code, region.name AS region_name,
                   ST_X(version.location::geometry) AS longitude,
                   ST_Y(version.location::geometry) AS latitude,
                   image.origin_img_url, image.image_name,
                   hanok.hours AS hanok_hours, hanok.parking AS hanok_parking,
                   hanok.homepage AS hanok_homepage,
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
            LEFT JOIN onmaru.catalog_place_image_versions image
              ON image.revision_id = version.revision_id
             AND image.place_id = version.place_id AND image.position = 0
            LEFT JOIN onmaru.catalog_hanok_detail_versions hanok
              ON hanok.revision_id = version.revision_id
             AND hanok.place_id = version.place_id
            WHERE active.dataset = 'kto-korean-tour'
              AND public_id.public_id = ?
            """;

    private final DataSource dataSource;

    private static final String SOURCE_SQL = """
            SELECT public_id.public_id, content.contentid, content.contenttypeid
            FROM onmaru.catalog_active_datasets active
            JOIN onmaru.catalog_place_versions version ON version.revision_id = active.revision_id
            JOIN onmaru.catalog_place_public_ids public_id ON public_id.place_id = version.place_id
            JOIN onmaru.catalog_kto_korean_content_versions content
              ON content.revision_id = version.revision_id
             AND content.source_ref_id = version.source_ref_id
            WHERE active.dataset = 'kto-korean-tour' AND public_id.public_id = ?
            """;

    public JdbcPlaceDetailStore(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource);
    }

    @Override
    public Optional<PlaceProjection> findByPlaceId(String placeId) {
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement(SQL)) {
            statement.setString(1, placeId);
            try (var rows = statement.executeQuery()) {
                return rows.next() ? Optional.of(map(rows)) : Optional.empty();
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("failed to read active place detail", exception);
        }
    }

    public Optional<TourApiPlaceReference> findTourApiReference(String placeId) {
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement(SOURCE_SQL)) {
            statement.setString(1, placeId);
            try (var rows = statement.executeQuery()) {
                if (!rows.next()) return Optional.empty();
                return Optional.of(new TourApiPlaceReference(
                        rows.getString("public_id"), rows.getString("contentid"), rows.getString("contenttypeid")));
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("failed to resolve TourAPI place reference", exception);
        }
    }

    public boolean hasStoredHanokDetail(String placeId) {
        String sql = """
                SELECT EXISTS (
                  SELECT 1
                  FROM onmaru.catalog_active_datasets active
                  JOIN onmaru.catalog_place_public_ids public_id ON public_id.public_id = ?
                  JOIN onmaru.catalog_hanok_detail_versions detail
                    ON detail.revision_id = active.revision_id AND detail.place_id = public_id.place_id
                  WHERE active.dataset = 'kto-korean-tour'
                )
                """;
        try (var connection = dataSource.getConnection(); var statement = connection.prepareStatement(sql)) {
            statement.setString(1, placeId);
            try (var rows = statement.executeQuery()) { rows.next(); return rows.getBoolean(1); }
        } catch (SQLException exception) {
            throw new IllegalStateException("failed to inspect stored hanok detail", exception);
        }
    }

    private PlaceProjection map(ResultSet row) throws SQLException {
        Double longitude = nullableDouble(row, "longitude");
        Double latitude = nullableDouble(row, "latitude");
        String imageUrl = row.getString("origin_img_url");
        var highlights = new java.util.ArrayList<String>();
        for (String value : new String[]{row.getString("hanok_hours"), row.getString("hanok_parking"), row.getString("hanok_homepage")}) {
            if (value != null && !value.isBlank()) highlights.add(value);
        }
        return new PlaceProjection(
                row.getString("public_id"), row.getString("name"), row.getString("category"),
                new RegionProjection(row.getString("region_code"), firstNonBlank(row.getString("region_name"), row.getString("address"))),
                row.getString("address"),
                longitude == null || latitude == null ? null : new CoordinatesProjection(latitude, longitude),
                imageUrl == null ? List.of() : List.of(new ImageProjection(imageUrl, row.getString("image_name"))),
                row.getString("overview"), List.copyOf(highlights), tags(row.getArray("tags")), null,
                "ACTIVE".equals(row.getString("status")) ? PlaceProjectionStatus.PUBLIC : PlaceProjectionStatus.HIDDEN,
                false);
    }

    private Double nullableDouble(ResultSet row, String name) throws SQLException {
        double value = row.getDouble(name);
        return row.wasNull() ? null : value;
    }

    private List<String> tags(Array value) throws SQLException {
        if (value == null) return List.of();
        try {
            return Arrays.stream((Object[]) value.getArray()).map(String::valueOf).toList();
        } finally {
            value.free();
        }
    }

    private String firstNonBlank(String first, String second) {
        return first != null && !first.isBlank() ? first : second;
    }
}
