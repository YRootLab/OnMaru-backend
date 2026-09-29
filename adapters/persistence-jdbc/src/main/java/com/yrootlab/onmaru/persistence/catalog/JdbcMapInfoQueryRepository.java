package com.yrootlab.onmaru.persistence.catalog;

import com.yrootlab.onmaru.catalog.application.query.mapinfo.*;

import javax.sql.DataSource;
import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class JdbcMapInfoQueryRepository implements MapInfoQueryPort {
    private static final String DATASET = "kto-korean-tour";
    private final DataSource dataSource;

    public JdbcMapInfoQueryRepository(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource);
    }

    @Override
    public MapInfoQueryResult find(MapInfoSqlQuery query) {
        try (var connection = dataSource.getConnection()) {
            var snapshot = snapshot(connection, query.snapshotId());
            var publication = publication(connection, snapshot.id());
            var total = count(connection, query, snapshot.id());
            var page = page(connection, query, snapshot.id());
            return new MapInfoQueryResult(snapshot, publication, total, page.items(), page.last(), page.more());
        } catch (SQLException exception) {
            throw new IllegalStateException("failed to query map information projection", exception);
        }
    }

    private MapInfoSnapshot snapshot(Connection c, String requested) throws SQLException {
        var sql = """
                SELECT revision.id, revision.published_at, revision.status::varchar
                FROM onmaru.catalog_active_datasets active
                JOIN onmaru.catalog_dataset_revisions revision ON revision.id = active.revision_id
                WHERE active.dataset = ? AND revision.status = 'PUBLISHED'
                  AND (? IS NULL OR revision.id = ?::uuid)
                """;
        try (var s = c.prepareStatement(sql)) {
            s.setString(1, DATASET);
            s.setString(2, requested);
            s.setString(3, requested);
            try (var r = s.executeQuery()) {
                if (!r.next()) throw new IllegalStateException("published map snapshot is unavailable");
                return new MapInfoSnapshot(r.getObject(1, java.util.UUID.class).toString(),
                        r.getObject(2, OffsetDateTime.class).toInstant(), r.getString(3));
            }
        }
    }

    private MapInfoProjectionPublication publication(Connection c, String revision) throws SQLException {
        try (var s = c.prepareStatement("""
                SELECT projection_name, revision_id, published_at, checksum, row_count, mapping_version
                FROM onmaru.map_projection_publications
                WHERE revision_id = ?::uuid AND projection_name = 'map_place_read_projection'
                """)) {
            s.setString(1, revision);
            try (var r = s.executeQuery()) {
                if (!r.next()) throw new IllegalStateException("map projection publication is unavailable");
                return new MapInfoProjectionPublication(r.getString(1), r.getObject(2, java.util.UUID.class).toString(),
                        r.getObject(3, OffsetDateTime.class).toInstant(), r.getString(4), r.getLong(5), r.getString(6));
            }
        }
    }

    private long count(Connection c, MapInfoSqlQuery q, String revision) throws SQLException {
        var sql = new StringBuilder("""
                SELECT count(DISTINCT place.place_id)
                FROM onmaru.map_place_read_projection place
                WHERE place.revision_id = ?::uuid AND place.status = 'ACTIVE'
                """);
        var args = new ArrayList<Object>();
        args.add(revision);
        appendFilters(sql, args, q);
        try (var s = c.prepareStatement(sql.toString())) {
            bind(s, c, args);
            try (var r = s.executeQuery()) {
                r.next();
                return r.getLong(1);
            }
        }
    }

    private Page page(Connection c, MapInfoSqlQuery q, String revision) throws SQLException {
        var sql = new StringBuilder("""
                SELECT place.public_id, place.name, place.display_category,
                       region.code, region.name,
                       ST_Y(place.location_geom), ST_X(place.location_geom),
                       place.thumbnail_url, place.summary,
                       place.sort_key,
                """);
        if ("DISTANCE".equals(q.sort())) {
            sql.append(" ST_Distance(place.location_geom::geography, ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography) AS distance_meters ");
        } else {
            sql.append(" 0.0 ");
        }
        sql.append("""
                FROM onmaru.map_place_read_projection place
                LEFT JOIN onmaru.catalog_regions region ON region.code = place.sigungu_code AND region.active
                WHERE place.revision_id = ?::uuid AND place.status = 'ACTIVE'
                """);
        var args = new ArrayList<Object>();
        if ("DISTANCE".equals(q.sort())) {
            args.add(q.lng());
            args.add(q.lat());
        }
        args.add(revision);
        appendFilters(sql, args, q);
        appendCursor(sql, args, q);
        sql.append(" ORDER BY ");
        if ("DISTANCE".equals(q.sort())) sql.append("distance_meters, place.place_id");
        else if ("NAME".equals(q.sort())) sql.append("place.name, place.place_id");
        else sql.append("COALESCE(region.name, ''), place.name, place.place_id");
        sql.append(" LIMIT ?");
        args.add(q.limit() + 1);
        try (var s = c.prepareStatement(sql.toString())) {
            bind(s, c, args);
            var items = new ArrayList<MapInfoPlaceItem>();
            MapInfoCursorPosition last = null;
            try (var r = s.executeQuery()) {
                while (r.next()) {
                    var item = new MapInfoPlaceItem(r.getString(1), r.getString(2), r.getString(3),
                            List.of(r.getString(3)), new MapInfoRegionRef(r.getString(4), r.getString(5)),
                            new MapInfoPoint(r.getDouble(6), r.getDouble(7)), r.getString(8), r.getString(9), false);
                    items.add(item);
                    var sortValue = "DISTANCE".equals(q.sort()) ? r.getString(10)
                            : "NAME".equals(q.sort()) ? r.getString(2) : r.getString(5) + "\u0000" + r.getString(2);
                    last = new MapInfoCursorPosition(sortValue, r.getDouble(11), r.getString(1));
                }
            }
            boolean more = items.size() > q.limit();
            if (more) items.remove(items.size() - 1);
            return new Page(List.copyOf(items), more ? last : null, more);
        }
    }

    private void appendFilters(StringBuilder sql, List<Object> args, MapInfoSqlQuery q) {
        if (q.bbox() != null) {
            sql.append(" AND place.location_geom && ST_MakeEnvelope(?, ?, ?, ?, 4326)");
            sql.append(" AND ST_Intersects(place.location_geom, ST_MakeEnvelope(?, ?, ?, ?, 4326))");
            addBbox(args, q.bbox());
            addBbox(args, q.bbox());
        }
        if (q.regionCode() != null) {
            sql.append(" AND (place.sido_code = ? OR place.sigungu_code = ?)");
            args.add(q.regionCode()); args.add(q.regionCode());
        }
        if (!q.canonicalCategories().isEmpty()) {
            sql.append("""
                    AND EXISTS (SELECT 1 FROM onmaru.map_place_category_projection category
                               WHERE category.revision_id = place.revision_id
                                 AND category.place_id = place.place_id
                                 AND category.canonical_category = ANY (?))
                    """);
            args.add(q.canonicalCategories());
        }
    }

    private void appendCursor(StringBuilder sql, List<Object> args, MapInfoSqlQuery q) {
        if (q.cursor() == null) return;
        if ("DISTANCE".equals(q.sort())) {
            sql.append(" AND (ST_Distance(place.location_geom::geography, ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography), place.place_id) > (?, ?)");
            args.add(q.lng()); args.add(q.lat()); args.add(q.cursor().distanceMeters()); args.add(q.cursor().placeId());
        } else if ("NAME".equals(q.sort()) || "REGION_NAME".equals(q.sort())) {
            sql.append(" AND (COALESCE(region.name, ''), place.name, place.place_id) > (?, ?, ?)");
            if ("NAME".equals(q.sort())) {
                args.add(""); args.add(q.cursor().sortValue());
            } else {
                var values = q.cursor().sortValue().split("\u0000", -1);
                args.add(values[0]); args.add(values.length > 1 ? values[1] : "");
            }
            args.add(q.cursor().placeId());
        }
    }

    private void addBbox(List<Object> args, MapInfoBounds b) {
        args.add(b.west()); args.add(b.south()); args.add(b.east()); args.add(b.north());
    }

    private void bind(PreparedStatement s, Connection c, List<Object> args) throws SQLException {
        for (int i = 0; i < args.size(); i++) {
            var value = args.get(i);
            if (value instanceof List<?> list) {
                Array array = c.createArrayOf("varchar", list.toArray());
                s.setArray(i + 1, array);
            } else if (value == null) s.setObject(i + 1, null);
            else s.setObject(i + 1, value);
        }
    }

    private record Page(List<MapInfoPlaceItem> items, MapInfoCursorPosition last, boolean more) {
    }
}
