package com.yrootlab.onmaru.persistence.catalog;

import com.yrootlab.onmaru.catalog.region.DataLabRegionMapping;
import com.yrootlab.onmaru.catalog.region.DataLabRegionMappingRegistry;
import com.yrootlab.onmaru.catalog.region.DataLabRegionMappingStatus;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/** JDBC registry that exposes every current mapping status for fail-closed collection decisions. */
public final class JdbcDataLabRegionMappingRegistry implements DataLabRegionMappingRegistry {

    private final DataSource dataSource;

    public JdbcDataLabRegionMappingRegistry(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public List<DataLabRegionMapping> findCurrent(LocalDate asOf) {
        if (asOf == null) {
            throw new IllegalArgumentException("asOf is required");
        }
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement("""
                     SELECT region.code, mapping.source_code, mapping.level::text, mapping.name,
                            mapping.source_url, mapping.source_observed_at, mapping.verified_by,
                            mapping.verified_at, mapping.status::text
                     FROM onmaru.catalog_datalab_region_mappings mapping
                     JOIN onmaru.catalog_region_source_codes source
                       ON source.provider = mapping.provider
                      AND source.dataset = mapping.dataset
                      AND source.source_code = mapping.source_code
                      AND source.valid_from = mapping.valid_from
                      AND source.region_id = mapping.region_id
                     JOIN onmaru.catalog_regions region ON region.id = mapping.region_id
                     WHERE region.active
                       AND source.valid_from <= ?
                       AND (source.valid_to IS NULL OR source.valid_to >= ?)
                     ORDER BY region.code
                     """)) {
            statement.setObject(1, asOf);
            statement.setObject(2, asOf);
            try (var result = statement.executeQuery()) {
                var mappings = new ArrayList<DataLabRegionMapping>();
                while (result.next()) {
                    var sourceObservedAt = result.getObject("source_observed_at", java.time.OffsetDateTime.class);
                    var verifiedAt = result.getObject("verified_at", java.time.OffsetDateTime.class);
                    mappings.add(new DataLabRegionMapping(
                            result.getString("code"),
                            result.getString("source_code"),
                            DataLabRegionMapping.Level.valueOf(result.getString("level")),
                            result.getString("name"),
                            result.getString("source_url"),
                            sourceObservedAt == null ? null : sourceObservedAt.toInstant(),
                            result.getString("verified_by"),
                            verifiedAt == null ? null : verifiedAt.toInstant(),
                            DataLabRegionMappingStatus.valueOf(result.getString("status"))));
                }
                return List.copyOf(mappings);
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed to query DataLab region mapping registry", exception);
        }
    }
}
