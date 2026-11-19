package com.yrootlab.onmaru.persistence.catalog.journey;

import com.yrootlab.onmaru.catalog.application.query.journey.ApprovedPlaceLookupItem;
import com.yrootlab.onmaru.catalog.application.query.journey.PlaceQueryPort;

import javax.sql.DataSource;
import java.util.List;

/** Approved public place lookup for the AI tool. Bounded to five places per call. */
public final class JdbcPlaceQueryAdapter implements PlaceQueryPort {

    private final DataSource dataSource;

    public JdbcPlaceQueryAdapter(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public List<ApprovedPlaceLookupItem> lookupApprovedPlaces(String regionCode, String category, int limit) {
        return lookupApprovedPlaces(regionCode, category, null, null, limit);
    }

    @Override
    public List<ApprovedPlaceLookupItem> lookupApprovedPlaces(
            String regionCode,
            String category,
            List<String> allowedPlaceIds,
            List<String> excludedPlaceIds,
            int limit) {
        return ApprovedPlaceReader.read(
                dataSource,
                regionCode,
                category,
                allowedPlaceIds,
                excludedPlaceIds,
                Math.min(Math.max(limit, 1), 5));
    }
}
