package com.yrootlab.onmaru.web.exploration;

import com.yrootlab.onmaru.journey.exploration.RegionCodeCatalog;

import javax.sql.DataSource;
import java.sql.SQLException;

/** Checks an explicit region code against the active catalog region table. Fails closed on read errors. */
final class CatalogRegionCodeCatalog implements RegionCodeCatalog {

    private static final String SQL = "SELECT 1 FROM onmaru.catalog_regions WHERE code = ? AND active";

    private final DataSource dataSource;

    CatalogRegionCodeCatalog(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public boolean isKnown(String regionCode) {
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement(SQL)) {
            statement.setString(1, regionCode);
            try (var rows = statement.executeQuery()) {
                return rows.next();
            }
        } catch (SQLException exception) {
            return false;
        }
    }
}
