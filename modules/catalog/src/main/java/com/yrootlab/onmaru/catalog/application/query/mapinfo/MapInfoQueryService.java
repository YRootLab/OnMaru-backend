package com.yrootlab.onmaru.catalog.application.query.mapinfo;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Application boundary for the information-mode list. All filtering/paging is delegated to the DB port. */
public final class MapInfoQueryService {
    private static final String SCHEMA_VERSION = "1.0";
    private static final int DEFAULT_LIMIT = 30;
    private static final int MAX_LIMIT = 100;
    private final MapInfoQueryPort port;
    private final MapInfoSavedStatePort savedStatePort;
    private final byte[] cursorSecret;

    public MapInfoQueryService(MapInfoQueryPort port) {
        this(port, MapInfoSavedStatePort.noOp(), "onmaru-map-info-cursor-v1");
    }

    public MapInfoQueryService(MapInfoQueryPort port, String cursorSecret) {
        this(port, MapInfoSavedStatePort.noOp(), cursorSecret);
    }

    public MapInfoQueryService(MapInfoQueryPort port, MapInfoSavedStatePort savedStatePort) {
        this(port, savedStatePort, "onmaru-map-info-cursor-v1");
    }

    public MapInfoQueryService(
            MapInfoQueryPort port,
            MapInfoSavedStatePort savedStatePort,
            String cursorSecret) {
        this.port = Objects.requireNonNull(port);
        this.savedStatePort = Objects.requireNonNull(savedStatePort);
        this.cursorSecret = Objects.requireNonNull(cursorSecret).getBytes(StandardCharsets.UTF_8);
    }

    public MapInfoListResponse list(MapInfoListQuery query) {
        return list(query, Optional.empty());
    }

    public MapInfoListResponse list(MapInfoListQuery query, Optional<UUID> memberId) {
        var normalized = normalize(query);
        var categories = MapInfoCategoryMapping.applied(normalized.category());
        var decoded = decode(normalized.cursor(), normalized, categories);
        var snapshotId = decoded == null ? normalized.snapshotId() : decoded.snapshotId();
        if (normalized.snapshotId() != null && decoded != null
                && !normalized.snapshotId().equals(decoded.snapshotId())) {
            throw new MapInfoQueryException("SNAPSHOT_EXPIRED", "snapshotId");
        }
        var cursor = decoded == null ? null : decoded.position();
        var queryWithSnapshot = new MapInfoListQuery(
                normalized.category(), normalized.regionCode(), normalized.bbox(), normalized.cursor(),
                snapshotId, normalized.language(), normalized.limit(), normalized.sort(), normalized.lat(), normalized.lng());
        var queryCategories = MapInfoCategoryMapping.queryValues(normalized.category());
        var result = port.find(new MapInfoSqlQuery(
                snapshotId, queryCategories, normalized.regionCode(), normalized.bbox(),
                normalized.sort(), normalized.lat(), normalized.lng(), normalized.limit(), cursor, null));
        var resolvedSnapshotId = result.snapshot().id();
        if (!result.snapshot().id().equals(queryWithSnapshot.snapshotId())) {
            if (queryWithSnapshot.snapshotId() != null) {
                throw new MapInfoQueryException("SNAPSHOT_EXPIRED", "snapshotId");
            }
        }
        var resolvedQuery = new MapInfoListQuery(normalized.category(), normalized.regionCode(), normalized.bbox(),
                normalized.cursor(), resolvedSnapshotId, normalized.language(), normalized.limit(), normalized.sort(), normalized.lat(), normalized.lng());
        var savedPlaceIds = savedStatePort.savedPlaceIds(memberId,
                result.items().stream().map(MapInfoPlaceItem::placeId).toList());
        var items = result.items().stream()
                .map(item -> withSavedState(item, savedPlaceIds.contains(item.placeId())))
                .toList();
        var next = result.hasMore() && result.lastCursor() != null
                ? encode(resolvedSnapshotId, resolvedQuery, categories, result.lastCursor()) : null;
        return new MapInfoListResponse(
                SCHEMA_VERSION, resolvedQuery, result.snapshot(), result.totalCount(), items, next,
                categories, result.hasMore() ? "PARTIAL" : "COMPLETE", result.publication());
    }

    private MapInfoListQuery normalize(MapInfoListQuery query) {
        if (query == null) throw new MapInfoQueryException("INVALID_REQUEST", "query");
        int limit = query.limit() == 0 ? DEFAULT_LIMIT : query.limit();
        if (limit < 1 || limit > MAX_LIMIT) throw new MapInfoQueryException("INVALID_REQUEST", "limit");
        var sort = query.sort() == null || query.sort().isBlank() ? "REGION_NAME" : query.sort().trim().toUpperCase();
        if (!sort.equals("REGION_NAME") && !sort.equals("NAME") && !sort.equals("DISTANCE")) {
            throw new MapInfoQueryException("INVALID_REQUEST", "sort");
        }
        validateBbox(query.bbox());
        if ((query.lat() == null) != (query.lng() == null)) throw new MapInfoQueryException("INVALID_REQUEST", "coordinates");
        if (query.lat() != null) {
            validateLat(query.lat(), "lat");
            validateLng(query.lng(), "lng");
        }
        if (sort.equals("DISTANCE") && (query.lat() == null || query.lng() == null)) {
            throw new MapInfoQueryException("INVALID_REQUEST", "coordinates");
        }
        return new MapInfoListQuery(query.category(), clean(query.regionCode()), query.bbox(), query.cursor(),
                clean(query.snapshotId()), query.language(), limit, sort, query.lat(), query.lng());
    }

    private DecodedCursor decode(String token, MapInfoListQuery query, List<String> categories) {
        if (token == null || token.isBlank()) return null;
        try {
            var parts = new String(Base64.getUrlDecoder().decode(token), StandardCharsets.UTF_8).split("\\.", -1);
            if (parts.length != 2 || !constantTime(parts[1], sign(parts[0]))) throw new IllegalArgumentException();
            var payload = parts[0].split("\\|", -1);
            if (payload.length != 8 || !Objects.equals(payload[1], query.category().name())
                    || !Objects.equals(payload[2], query.sort())
                    || !Objects.equals(payload[3], String.join(",", categories))
                    || !Objects.equals(payload[4], querySignature(query))) throw new IllegalArgumentException();
            return new DecodedCursor(payload[0],
                    new MapInfoCursorPosition(payload[5], Double.parseDouble(payload[6]), payload[7]));
        } catch (Exception exception) {
            if (exception instanceof MapInfoQueryException queryException) throw queryException;
            throw new MapInfoQueryException("INVALID_REQUEST", "cursor");
        }
    }

    private String encode(String snapshotId, MapInfoListQuery query, List<String> categories, MapInfoCursorPosition cursor) {
        var payload = String.join("|", snapshotId, query.category().name(), query.sort(), String.join(",", categories),
                querySignature(query), cursor.sortValue(), Double.toString(cursor.distanceMeters()), cursor.placeId());
        return Base64.getUrlEncoder().withoutPadding().encodeToString((payload + "." + sign(payload)).getBytes(StandardCharsets.UTF_8));
    }

    private String querySignature(MapInfoListQuery query) {
        return String.join("~", query.regionCode() == null ? "" : query.regionCode(),
                query.bbox() == null ? "" : query.bbox().west() + "," + query.bbox().south() + ","
                        + query.bbox().east() + "," + query.bbox().north(),
                query.lat() == null ? "" : query.lat().toString(),
                query.lng() == null ? "" : query.lng().toString());
    }

    private MapInfoPlaceItem withSavedState(MapInfoPlaceItem item, boolean savedByMe) {
        return new MapInfoPlaceItem(item.placeId(), item.name(), item.displayCategory(), item.matchedCategories(),
                item.region(), item.coordinates(), item.thumbnailUrl(), item.summary(), savedByMe);
    }

    private String sign(String value) {
        try {
            var mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(cursorSecret, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("failed to sign map cursor", exception);
        }
    }

    private boolean constantTime(String left, String right) {
        return java.security.MessageDigest.isEqual(left.getBytes(StandardCharsets.UTF_8), right.getBytes(StandardCharsets.UTF_8));
    }

    private void validateBbox(MapInfoBounds bbox) {
        if (bbox == null) return;
        validateLng(bbox.west(), "bbox"); validateLng(bbox.east(), "bbox");
        validateLat(bbox.south(), "bbox"); validateLat(bbox.north(), "bbox");
        if (bbox.west() >= bbox.east() || bbox.south() >= bbox.north()) throw new MapInfoQueryException("INVALID_REQUEST", "bbox");
    }

    private void validateLat(double value, String field) {
        if (!Double.isFinite(value) || value < -90 || value > 90) throw new MapInfoQueryException("INVALID_REQUEST", field);
    }

    private void validateLng(double value, String field) {
        if (!Double.isFinite(value) || value < -180 || value > 180) throw new MapInfoQueryException("INVALID_REQUEST", field);
    }

    private String clean(String value) { return value == null || value.isBlank() ? null : value.trim(); }

    private record DecodedCursor(String snapshotId, MapInfoCursorPosition position) { }
}
