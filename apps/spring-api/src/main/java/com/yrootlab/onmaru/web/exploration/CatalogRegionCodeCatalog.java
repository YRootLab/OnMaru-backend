package com.yrootlab.onmaru.web.exploration;

import com.yrootlab.onmaru.journey.exploration.RegionCodeCatalog;
import com.yrootlab.onmaru.journey.region.CanonicalRegionAliases;

import javax.sql.DataSource;
import java.sql.SQLException;

/** Checks an explicit region code against the active catalog region table. Fails closed on read errors. */
final class CatalogRegionCodeCatalog implements RegionCodeCatalog {

    private static final String SQL = "SELECT 1 FROM onmaru.catalog_regions WHERE code = ? AND active";

    private final DataSource dataSource;

    CatalogRegionCodeCatalog(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /** A canonical code is known when one of its catalog codes exists and is active. */
    @Override
    public boolean isKnown(String regionCode) {
        for (String catalogCode : CanonicalRegionAliases.catalogCodesFor(regionCode)) {
            if (existsInCatalog(catalogCode)) {
                return true;
            }
        }
        return false;
    }

    private boolean existsInCatalog(String catalogCode) {
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement(SQL)) {
            statement.setString(1, catalogCode);
            try (var rows = statement.executeQuery()) {
                return rows.next();
            }
        } catch (SQLException exception) {
            return false;
        }
    }
}
