package com.yrootlab.onmaru.persistence.mapcollab;

import com.yrootlab.onmaru.mapcollab.port.CatalogPlaceInfo;
import com.yrootlab.onmaru.mapcollab.port.PlaceCatalogPort;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Objects;
import java.util.Optional;

public class JdbcPlaceCatalogAdapter implements PlaceCatalogPort {

    private final DataSource dataSource;

    private static final String SQL = """
            SELECT public_id.public_id,
                   COALESCE(version.name, ext.name) AS name,
                   COALESCE(ST_Y(version.location::geometry), ST_Y(ext.location::geometry)) AS latitude,
                   COALESCE(ST_X(version.location::geometry), ST_X(ext.location::geometry)) AS longitude
            FROM onmaru.catalog_place_public_ids public_id
            LEFT JOIN (
                SELECT v.place_id, v.name, v.location
                FROM onmaru.catalog_place_versions v
                JOIN onmaru.catalog_active_datasets a ON a.revision_id = v.revision_id
                WHERE a.dataset = 'kto-korean-tour'
            ) version ON version.place_id = public_id.place_id
            LEFT JOIN onmaru.catalog_external_places ext ON ext.place_id = public_id.place_id
            WHERE public_id.public_id = ?
            """;

    public JdbcPlaceCatalogAdapter(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource must not be null");
    }

    @Override
    public Optional<CatalogPlaceInfo> findPlaceByPublicId(String publicId) {
        if (publicId == null || publicId.isBlank()) {
            return Optional.empty();
        }
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(SQL)) {
            ps.setString(1, publicId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    String name = rs.getString("name");
                    Double lat = rs.getObject("latitude", Double.class);
                    Double lng = rs.getObject("longitude", Double.class);
                    if (name != null && lat != null && lng != null) {
                        return Optional.of(new CatalogPlaceInfo(rs.getString("public_id"), name, lat, lng));
                    }
                }
                return Optional.empty();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to query catalog place by publicId: " + publicId, e);
        }
    }
}
