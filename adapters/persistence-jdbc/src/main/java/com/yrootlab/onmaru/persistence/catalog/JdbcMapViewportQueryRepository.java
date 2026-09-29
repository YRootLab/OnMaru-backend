package com.yrootlab.onmaru.persistence.catalog;

import com.yrootlab.onmaru.catalog.application.query.mapinfo.MapInfoBounds;
import com.yrootlab.onmaru.catalog.application.query.mapinfo.MapInfoCategory;
import com.yrootlab.onmaru.catalog.application.query.mapinfo.MapInfoCategoryMapping;
import com.yrootlab.onmaru.catalog.application.query.mapinfo.MapInfoPoint;
import com.yrootlab.onmaru.catalog.application.query.mapinfo.MapInfoProjectionPublication;
import com.yrootlab.onmaru.catalog.application.query.mapinfo.MapInfoRenderMode;
import com.yrootlab.onmaru.catalog.application.query.mapinfo.MapInfoSnapshot;
import com.yrootlab.onmaru.catalog.application.query.mapinfo.MapInfoSnapshotResolver;
import com.yrootlab.onmaru.catalog.application.query.mapinfo.MapInfoViewportItem;
import com.yrootlab.onmaru.catalog.application.query.mapinfo.MapInfoViewportQuery;
import com.yrootlab.onmaru.catalog.application.query.mapinfo.MapInfoViewportResponse;
import com.yrootlab.onmaru.catalog.application.query.mapinfo.MapInfoViewportStore;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * SQL-backed map viewport read model. Every mode is served from W1 projections;
 * the source catalog is never materialized in the JVM.
 */
public final class JdbcMapViewportQueryRepository implements MapInfoViewportStore, MapInfoSnapshotResolver {

    private static final String DATASET = "kto-korean-tour";
    private static final String PROFILE_VERSION = "map-zoom-v1";
    private static final String PUBLICATION = "map_place_read_projection";

    private final DataSource dataSource;
    private final Duration statementTimeout;

    public JdbcMapViewportQueryRepository(DataSource dataSource) {
        this(dataSource, Duration.ofMillis(1500));
    }

    public JdbcMapViewportQueryRepository(DataSource dataSource, Duration statementTimeout) {
        this.dataSource = Objects.requireNonNull(dataSource);
        this.statementTimeout = Objects.requireNonNull(statementTimeout);
    }

    @Override
    public MapInfoViewportResponse find(MapInfoViewportQuery query) {
        var mode = mode(query.zoomLevel());
        try (var connection = dataSource.getConnection()) {
            connection.setReadOnly(true);
            try (var timeout = connection.createStatement()) {
                timeout.execute("SET statement_timeout = '" + statementTimeout.toMillis() + "ms'");
            }
            var snapshot = snapshot(connection, query.snapshotId());
            var publication = publication(connection, snapshot.id());
            var total = totalCount(connection, snapshot.id(), query);
            var items = switch (mode) {
                case PLACE -> places(connection, snapshot.id(), query);
                case CLUSTER -> clusters(connection, snapshot.id(), query);
                case DISTRICT, REGION -> regions(connection, snapshot.id(), query, mode);
            };
            return new MapInfoViewportResponse(
                    "1.0", mode, PROFILE_VERSION, snapshot, total, items,
                    MapInfoCategoryMapping.applied(query.category()), "COMPLETE", query.bbox(), publication);
        } catch (SQLException exception) {
            throw new IllegalStateException("failed to query map information viewport", exception);
        }
    }

    @Override
    public MapInfoSnapshot currentSnapshot() {
        try (var connection = dataSource.getConnection()) {
            return snapshot(connection, null);
        } catch (SQLException exception) {
            throw new IllegalStateException("failed to resolve active map snapshot", exception);
        }
    }

    private MapInfoRenderMode mode(int zoom) {
        if (zoom <= 5) return MapInfoRenderMode.PLACE;
        if (zoom <= 7) return MapInfoRenderMode.CLUSTER;
        if (zoom <= 10) return MapInfoRenderMode.DISTRICT;
        return MapInfoRenderMode.REGION;
    }

    private int targetZoomLevel(MapInfoRenderMode mode) {
        return switch (mode) {
            case PLACE -> 5;
            case CLUSTER -> 7;
            case DISTRICT -> 10;
            case REGION -> 14;
        };
    }

    private MapInfoSnapshot snapshot(Connection connection, String requested) throws SQLException {
        UUID requestedId = requested == null ? null : UUID.fromString(requested);
        String sql = """
                SELECT revision.id, revision.published_at, revision.status::varchar
                FROM onmaru.catalog_active_datasets active
                JOIN onmaru.catalog_dataset_revisions revision ON revision.id = active.revision_id
                WHERE active.dataset = ? AND revision.status = 'PUBLISHED'
                  AND (?::uuid IS NULL OR revision.id = ?::uuid)
                """;
        try (var statement = connection.prepareStatement(sql)) {
            statement.setString(1, DATASET);
            if (requestedId == null) statement.setNull(2, java.sql.Types.OTHER);
            else statement.setObject(2, requestedId);
            if (requestedId == null) statement.setNull(3, java.sql.Types.OTHER);
            else statement.setObject(3, requestedId);
            try (var rows = statement.executeQuery()) {
                if (!rows.next()) throw new IllegalStateException("published map snapshot is unavailable");
                return new MapInfoSnapshot(
                        rows.getObject(1, UUID.class).toString(),
                        rows.getObject(2, java.time.OffsetDateTime.class).toInstant(),
                        rows.getString(3));
            }
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("snapshot id is not a UUID", exception);
        }
    }

    private MapInfoProjectionPublication publication(Connection connection, String revisionId) throws SQLException {
        try (var statement = connection.prepareStatement("""
                SELECT projection_name, revision_id, published_at, checksum, row_count, mapping_version
                FROM onmaru.map_projection_publications
                WHERE revision_id = ?::uuid AND projection_name = ?
                """)) {
            statement.setString(1, revisionId);
            statement.setString(2, PUBLICATION);
            try (var rows = statement.executeQuery()) {
                if (!rows.next()) throw new IllegalStateException("map projection publication is unavailable");
                return new MapInfoProjectionPublication(
                        rows.getString(1), rows.getObject(2, UUID.class).toString(),
                        rows.getObject(3, java.time.OffsetDateTime.class).toInstant(),
                        rows.getString(4), rows.getLong(5), rows.getString(6));
            }
        }
    }

    private long totalCount(Connection connection, String revisionId, MapInfoViewportQuery query) throws SQLException {
        var sql = """
                SELECT count(*)
                FROM onmaru.map_place_read_projection place
                WHERE place.revision_id = ?::uuid
                  AND place.status = 'ACTIVE'
                  AND place.location_geom && ST_MakeEnvelope(?, ?, ?, ?, 4326)
                  AND ST_Intersects(place.location_geom, ST_MakeEnvelope(?, ?, ?, ?, 4326))
                  AND (? = 'ALL' OR EXISTS (
                      SELECT 1 FROM onmaru.map_place_category_projection category
                      WHERE category.revision_id = place.revision_id
                        AND category.place_id = place.place_id
                        AND category.canonical_category = ANY (?)))
                  AND (? IS NULL OR place.sido_code = ? OR place.sigungu_code = ?)
                """;
        try (var statement = connection.prepareStatement(sql)) {
            int i = 1;
            statement.setString(i++, revisionId);
            bindBbox(statement, i, query.bbox()); i += 4;
            bindBbox(statement, i, query.bbox()); i += 4;
            statement.setString(i++, query.category().name());
            bindCategoryArray(connection, statement, i++, query);
            statement.setString(i++, query.regionCode());
            statement.setString(i++, query.regionCode());
            statement.setString(i, query.regionCode());
            try (var rows = statement.executeQuery()) {
                rows.next();
                return rows.getLong(1);
            }
        }
    }

    private List<MapInfoViewportItem> places(Connection connection, String revisionId, MapInfoViewportQuery query)
            throws SQLException {
        var sql = """
                SELECT place.public_id, place.name, place.display_category,
                       ST_Y(place.location_geom), ST_X(place.location_geom)
                FROM onmaru.map_place_read_projection place
                WHERE place.revision_id = ?::uuid AND place.status = 'ACTIVE'
                  AND place.location_geom && ST_MakeEnvelope(?, ?, ?, ?, 4326)
                  AND ST_Intersects(place.location_geom, ST_MakeEnvelope(?, ?, ?, ?, 4326))
                  AND (? = 'ALL' OR EXISTS (
                      SELECT 1 FROM onmaru.map_place_category_projection category
                      WHERE category.revision_id = place.revision_id
                        AND category.place_id = place.place_id
                        AND category.canonical_category = ANY (?)))
                  AND (? IS NULL OR place.sido_code = ? OR place.sigungu_code = ?)
                ORDER BY place.sort_key, place.place_id
                LIMIT ?
                """;
        var items = new ArrayList<MapInfoViewportItem>();
        try (var statement = connection.prepareStatement(sql)) {
            int i = 1;
            statement.setString(i++, revisionId);
            bindBbox(statement, i, query.bbox()); i += 4;
            bindBbox(statement, i, query.bbox()); i += 4;
            statement.setString(i++, query.category().name());
            bindCategoryArray(connection, statement, i++, query);
            statement.setString(i++, query.regionCode());
            statement.setString(i++, query.regionCode());
            statement.setString(i++, query.regionCode());
            statement.setInt(i, query.limit());
            try (var rows = statement.executeQuery()) {
                while (rows.next()) {
                    var category = rows.getString(3);
                    items.add(new MapInfoViewportItem(
                            "PLACE", rows.getString(1), rows.getString(2),
                            new MapInfoPoint(rows.getDouble(4), rows.getDouble(5)), null, 1,
                            Map.of(category, 1L), targetZoomLevel(MapInfoRenderMode.PLACE), rows.getString(1), category, null));
                }
            }
        }
        return List.copyOf(items);
    }

    private List<MapInfoViewportItem> clusters(Connection connection, String revisionId, MapInfoViewportQuery query)
            throws SQLException {
        double width = query.bbox().east() - query.bbox().west();
        double height = query.bbox().north() - query.bbox().south();
        double cell = Math.max(width, height) / Math.max(4.0, Math.sqrt(query.limit()));
        var sql = """
                WITH candidates AS (
                    SELECT place.place_id, category.canonical_category, place.location_geom,
                           floor((ST_X(place.location_geom) - ?) / ?) AS gx,
                           floor((ST_Y(place.location_geom) - ?) / ?) AS gy
                    FROM onmaru.map_place_read_projection place
                    JOIN onmaru.map_place_category_projection category
                      ON category.revision_id = place.revision_id
                     AND category.place_id = place.place_id
                    WHERE place.revision_id = ?::uuid AND place.status = 'ACTIVE'
                      AND place.location_geom && ST_MakeEnvelope(?, ?, ?, ?, 4326)
                      AND (? = 'ALL' OR category.canonical_category = ANY (?))
                      AND (? IS NULL OR place.sido_code = ? OR place.sigungu_code = ?)
                ), grouped AS (
                    SELECT gx, gy, count(DISTINCT place_id) AS count,
                           ST_Centroid(ST_Collect(location_geom)) AS center,
                           ST_Envelope(ST_Extent(location_geom)) AS bounds
                    FROM candidates GROUP BY gx, gy
                    ORDER BY gx, gy
                    LIMIT ?
                ), category_grouped AS (
                    SELECT gx, gy, canonical_category, count(DISTINCT place_id) AS category_count
                    FROM candidates GROUP BY gx, gy, canonical_category
                )
                SELECT grouped.gx, grouped.gy, grouped.count, ST_Y(grouped.center), ST_X(grouped.center),
                       ST_XMin(grouped.bounds), ST_YMin(grouped.bounds), ST_XMax(grouped.bounds), ST_YMax(grouped.bounds),
                       category_grouped.canonical_category, category_grouped.category_count
                FROM grouped
                JOIN category_grouped ON category_grouped.gx = grouped.gx AND category_grouped.gy = grouped.gy
                ORDER BY grouped.gx, grouped.gy, category_grouped.canonical_category
                """;
        var aggregates = new LinkedHashMap<String, ClusterAggregate>();
        try (var statement = connection.prepareStatement(sql)) {
            int i = 1;
            statement.setDouble(i++, query.bbox().west()); statement.setDouble(i++, cell);
            statement.setDouble(i++, query.bbox().south()); statement.setDouble(i++, cell);
            statement.setString(i++, revisionId);
            bindBbox(statement, i, query.bbox()); i += 4;
            statement.setString(i++, query.category().name());
            bindCategoryArray(connection, statement, i++, query);
            statement.setString(i++, query.regionCode()); statement.setString(i++, query.regionCode());
            statement.setString(i++, query.regionCode());
            statement.setInt(i++, query.limit());
            try (var rows = statement.executeQuery()) {
                while (rows.next()) {
                    int gx = rows.getInt(1), gy = rows.getInt(2);
                    String id = "cluster:" + query.zoomLevel() + ":" + gx + ":" + gy;
                    var aggregate = aggregates.get(id);
                    if (aggregate == null) {
                        aggregate = new ClusterAggregate(id, rows.getLong(3),
                                new MapInfoPoint(rows.getDouble(4), rows.getDouble(5)),
                                new MapInfoBounds(rows.getDouble(6), rows.getDouble(7), rows.getDouble(8), rows.getDouble(9)));
                        aggregates.put(id, aggregate);
                    }
                    aggregate.categoryCounts.put(rows.getString(10), rows.getLong(11));
                }
            }
        }
        return aggregates.values().stream().map(aggregate -> new MapInfoViewportItem(
                "CLUSTER", aggregate.id, null, aggregate.center, aggregate.bounds, aggregate.count,
                aggregate.categoryCounts, targetZoomLevel(MapInfoRenderMode.CLUSTER), null, null, null)).toList();
    }

    private List<MapInfoViewportItem> regions(
            Connection connection, String revisionId, MapInfoViewportQuery query, MapInfoRenderMode mode)
            throws SQLException {
        String scope = mode == MapInfoRenderMode.DISTRICT ? "DISTRICT" : "REGION";
        var sql = """
                WITH latest_boundaries AS (
                    SELECT DISTINCT ON (boundary.region_id)
                           boundary.region_id, boundary.geometry
                    FROM onmaru.catalog_region_boundaries boundary
                    ORDER BY boundary.region_id, boundary.boundary_revision DESC
                ), scoped AS (
                    SELECT region.code, region.name, boundary.geometry,
                           count_projection.canonical_category, count_projection.place_count
                    FROM onmaru.catalog_regions region
                    JOIN latest_boundaries boundary ON boundary.region_id = region.id
                    JOIN onmaru.map_scope_count_projection count_projection
                      ON count_projection.region_code = region.code
                     AND count_projection.scope_type = ?
                     AND count_projection.revision_id = ?::uuid
                    WHERE region.active
                      AND boundary.geometry && ST_MakeEnvelope(?, ?, ?, ?, 4326)
                      AND ST_Intersects(boundary.geometry, ST_MakeEnvelope(?, ?, ?, ?, 4326))
                      AND (? IS NULL OR region.code = ? OR EXISTS (
                          SELECT 1 FROM onmaru.catalog_regions parent
                          WHERE parent.id = region.parent_id AND parent.code = ?))
                      AND (? = 'ALL' OR count_projection.canonical_category = ANY (?))
                )
                SELECT code, name,
                       ST_Y(ST_Centroid(ST_Collect(geometry))), ST_X(ST_Centroid(ST_Collect(geometry))),
                       ST_XMin(ST_Envelope(ST_Collect(geometry))), ST_YMin(ST_Envelope(ST_Collect(geometry))),
                       ST_XMax(ST_Envelope(ST_Collect(geometry))), ST_YMax(ST_Envelope(ST_Collect(geometry))),
                       sum(place_count), canonical_category
                FROM scoped
                GROUP BY code, name, canonical_category
                ORDER BY code, canonical_category
                """;
        Map<String, Aggregate> aggregates = new LinkedHashMap<>();
        try (var statement = connection.prepareStatement(sql)) {
            int i = 1;
            statement.setString(i++, scope); statement.setString(i++, revisionId);
            bindBbox(statement, i, query.bbox()); i += 4; bindBbox(statement, i, query.bbox()); i += 4;
            statement.setString(i++, query.regionCode()); statement.setString(i++, query.regionCode());
            statement.setString(i++, query.regionCode());
            statement.setString(i++, query.category().name());
            bindCategoryArray(connection, statement, i++, query);
            try (var rows = statement.executeQuery()) {
                while (rows.next()) {
                    var code = rows.getString(1);
                    var aggregate = aggregates.get(code);
                    if (aggregate == null) {
                        aggregate = new Aggregate(
                                code, rows.getString(2),
                                new MapInfoPoint(rows.getDouble(3), rows.getDouble(4)),
                                new MapInfoBounds(rows.getDouble(5), rows.getDouble(6), rows.getDouble(7), rows.getDouble(8)));
                        aggregates.put(code, aggregate);
                    }
                    aggregate.categoryCounts.put(rows.getString(10), rows.getLong(9));
                }
            }
        }
        return aggregates.values().stream().limit(query.limit()).map(aggregate -> new MapInfoViewportItem(
                mode.name(), mode.name().toLowerCase() + ":" + aggregate.code, aggregate.name,
                aggregate.center, aggregate.bounds, aggregate.categoryCounts.values().stream().mapToLong(Long::longValue).sum(),
                aggregate.categoryCounts, targetZoomLevel(mode), null, null, aggregate.code)).toList();
    }

    private void bindBbox(PreparedStatement statement, int index, MapInfoBounds bbox) throws SQLException {
        statement.setDouble(index, bbox.west()); statement.setDouble(index + 1, bbox.south());
        statement.setDouble(index + 2, bbox.east()); statement.setDouble(index + 3, bbox.north());
    }

    private void bindCategoryArray(Connection connection, PreparedStatement statement, int index,
                                   MapInfoViewportQuery query) throws SQLException {
        var categories = MapInfoCategoryMapping.queryValues(query.category());
        statement.setArray(index, connection.createArrayOf("varchar", categories.toArray(String[]::new)));
    }

    private static final class Aggregate {
        private final String code;
        private final String name;
        private final MapInfoPoint center;
        private final MapInfoBounds bounds;
        private final Map<String, Long> categoryCounts = new LinkedHashMap<>();

        private Aggregate(String code, String name, MapInfoPoint center, MapInfoBounds bounds) {
            this.code = code; this.name = name; this.center = center; this.bounds = bounds;
        }
    }

    private static final class ClusterAggregate {
        private final String id;
        private final long count;
        private final MapInfoPoint center;
        private final MapInfoBounds bounds;
        private final Map<String, Long> categoryCounts = new LinkedHashMap<>();

        private ClusterAggregate(String id, long count, MapInfoPoint center, MapInfoBounds bounds) {
            this.id = id; this.count = count; this.center = center; this.bounds = bounds;
        }
    }
}
