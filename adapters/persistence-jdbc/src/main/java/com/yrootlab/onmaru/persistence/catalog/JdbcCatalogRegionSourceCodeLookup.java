package com.yrootlab.onmaru.persistence.catalog;

import com.yrootlab.onmaru.catalog.region.CatalogRegionSourceCode;
import com.yrootlab.onmaru.catalog.region.CatalogRegionSourceCodeLookup;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/** JDBC implementation of the validity-aware Catalog region source-code lookup. */
public final class JdbcCatalogRegionSourceCodeLookup implements CatalogRegionSourceCodeLookup {

    private final DataSource dataSource;

    public JdbcCatalogRegionSourceCodeLookup(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public List<CatalogRegionSourceCode> findCurrent(String provider, String dataset, LocalDate asOf) {
        if (provider == null || provider.isBlank() || dataset == null || dataset.isBlank() || asOf == null) {
            throw new IllegalArgumentException("provider, dataset and asOf are required");
        }
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement("""
                     SELECT DISTINCT ON (region.code) region.code, source.source_code
                     FROM onmaru.catalog_region_source_codes source
                     JOIN onmaru.catalog_regions region ON region.id = source.region_id
                     JOIN onmaru.catalog_region_source_code_verifications verification
                       ON verification.provider = source.provider
                      AND verification.dataset = source.dataset
                      AND verification.source_code = source.source_code
                      AND verification.valid_from = source.valid_from
                     WHERE source.provider = ?
                       AND source.dataset = ?
                       AND region.active
                       AND source.valid_from <= ?
                       AND (source.valid_to IS NULL OR source.valid_to >= ?)
                     ORDER BY region.code, source.valid_from DESC, source.source_code
                     """)) {
            statement.setString(1, provider);
            statement.setString(2, dataset);
            statement.setObject(3, asOf);
            statement.setObject(4, asOf);
            try (var result = statement.executeQuery()) {
                var sourceCodes = new ArrayList<CatalogRegionSourceCode>();
                while (result.next()) {
                    sourceCodes.add(new CatalogRegionSourceCode(
                            result.getString("code"), result.getString("source_code")));
                }
                return List.copyOf(sourceCodes);
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed to query Catalog region source codes", exception);
        }
    }
}
