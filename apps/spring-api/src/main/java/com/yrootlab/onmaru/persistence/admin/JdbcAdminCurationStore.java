package com.yrootlab.onmaru.persistence.admin;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yrootlab.onmaru.admin.curation.AdminCuration;
import com.yrootlab.onmaru.admin.curation.AdminCurationStore;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class JdbcAdminCurationStore implements AdminCurationStore {
    private final DataSource dataSource;
    private final ObjectMapper mapper;

    public JdbcAdminCurationStore(DataSource dataSource, ObjectMapper mapper) {
        this.dataSource = dataSource;
        this.mapper = mapper;
    }

    @Override
    public List<AdminCuration> find(String category, Boolean included, int limit) {
        StringBuilder sql = new StringBuilder("""
                SELECT id, canonical_place_id, category, included, badges, source_revision_id, version, updated_by, updated_at
                FROM (
                    SELECT DISTINCT ON (canonical_place_id, category)
                           id, canonical_place_id, category, included, badges, source_revision_id,
                           version, updated_by, updated_at
                    FROM onmaru.catalog_admin_curation_overrides
                    ORDER BY canonical_place_id, category, version DESC, updated_at DESC, id DESC
                ) latest
                WHERE 1=1
                """);
        if (category != null) sql.append(" AND category = ?");
        if (included != null) sql.append(" AND included = ?");
        sql.append(" ORDER BY updated_at DESC, id DESC LIMIT ?");
        try (var connection = dataSource.getConnection(); var statement = connection.prepareStatement(sql.toString())) {
            int index = 1;
            if (category != null) statement.setString(index++, category);
            if (included != null) statement.setBoolean(index++, included);
            statement.setInt(index, limit);
            try (var result = statement.executeQuery()) {
                List<AdminCuration> items = new ArrayList<>();
                while (result.next()) items.add(read(result));
                return items;
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed to load admin curations", exception);
        }
    }

    @Override
    public AdminCuration upsert(UUID placeId, String category, boolean included, List<String> badges, UUID adminId) {
        UUID id = UUID.randomUUID();
        try (var connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            long version;
            try (var query = connection.prepareStatement("""
                    SELECT COALESCE(MAX(version), 0) + 1 FROM onmaru.catalog_admin_curation_overrides
                    WHERE canonical_place_id = ? AND category = ?
                    """)) {
                query.setObject(1, placeId); query.setString(2, category);
                try (var result = query.executeQuery()) { result.next(); version = result.getLong(1); }
            }
            try (var insert = connection.prepareStatement("""
                    INSERT INTO onmaru.catalog_admin_curation_overrides
                        (id, canonical_place_id, category, included, badges, version, updated_by)
                    VALUES (?, ?, ?, ?, ?::jsonb, ?, ?)
                    """)) {
                insert.setObject(1, id); insert.setObject(2, placeId); insert.setString(3, category);
                insert.setBoolean(4, included); insert.setString(5, mapper.writeValueAsString(badges));
                insert.setLong(6, version); insert.setObject(7, adminId); insert.executeUpdate();
            }
            connection.commit();
            return new AdminCuration(id, placeId, category, included, badges, null, version, adminId, java.time.Instant.now());
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to save admin curation", exception);
        }
    }

    private AdminCuration read(java.sql.ResultSet result) throws SQLException {
        try {
            return new AdminCuration(result.getObject("id", UUID.class), result.getObject("canonical_place_id", UUID.class),
                    result.getString("category"), result.getBoolean("included"),
                    mapper.readValue(result.getString("badges"), new TypeReference<>() {}),
                    result.getObject("source_revision_id", UUID.class), result.getLong("version"),
                    result.getObject("updated_by", UUID.class), result.getObject("updated_at", OffsetDateTime.class).toInstant());
        } catch (Exception exception) {
            throw new SQLException("Invalid curation badges JSON", exception);
        }
    }
}
