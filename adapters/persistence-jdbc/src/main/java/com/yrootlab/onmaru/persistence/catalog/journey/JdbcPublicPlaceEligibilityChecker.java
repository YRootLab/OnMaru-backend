package com.yrootlab.onmaru.persistence.catalog.journey;

import com.yrootlab.onmaru.journey.worker.plan.AiJourneyContracts.Filters;
import com.yrootlab.onmaru.journey.worker.plan.JourneyPlanExecutionPorts.PublicPlaceEligibilityChecker;

import javax.sql.DataSource;
import java.sql.Array;
import java.sql.SQLException;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Verifies AI-returned public place IDs post-execution against the active published catalog revision and the
 * approved authority filters without restricting AI search to pre-fetched candidates.
 */
public final class JdbcPublicPlaceEligibilityChecker implements PublicPlaceEligibilityChecker {

    static final String DATASET = ApprovedPlaceReader.DATASET;

    private static final String SQL = """
            SELECT public_id.public_id,
                   region.code AS region_code,
                   version.category AS category_code
            FROM onmaru.catalog_active_datasets active
            JOIN onmaru.catalog_dataset_revisions revision
              ON revision.id = active.revision_id
             AND revision.status = 'PUBLISHED'
            JOIN onmaru.catalog_place_versions version
              ON version.revision_id = active.revision_id
            JOIN onmaru.catalog_place_public_ids public_id
              ON public_id.place_id = version.place_id
            LEFT JOIN onmaru.catalog_regions region
              ON region.id = version.region_id
            WHERE active.dataset = ?
              AND version.status = 'ACTIVE'
              AND public_id.public_id = ANY(?)
            """;

    private final DataSource dataSource;

    public JdbcPublicPlaceEligibilityChecker(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource must not be null");
    }

    @Override
    public Set<String> eligiblePlaceIds(List<String> placeIds, Filters approvedFilters) {
        if (placeIds == null || placeIds.isEmpty()) {
            return Set.of();
        }
        Set<String> allowedRegions = approvedFilters == null || approvedFilters.regionCodes() == null
                ? null : new HashSet<>(approvedFilters.regionCodes());
        Set<String> allowedCategories = approvedFilters == null || approvedFilters.categoryCodes() == null
                ? null : new HashSet<>(approvedFilters.categoryCodes());
        Set<String> allowedPlaceIds = approvedFilters == null || approvedFilters.placeIds() == null
                ? null : new HashSet<>(approvedFilters.placeIds());
        Set<String> excludedPlaceIds = approvedFilters == null || approvedFilters.excludedPlaceIds() == null
                ? Set.of() : new HashSet<>(approvedFilters.excludedPlaceIds());

        Set<String> eligible = new LinkedHashSet<>();
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement(SQL)) {
            statement.setString(1, DATASET);
            Array idsArray = connection.createArrayOf("text", placeIds.toArray(String[]::new));
            try {
                statement.setArray(2, idsArray);
                try (var rows = statement.executeQuery()) {
                    while (rows.next()) {
                        String placeId = rows.getString("public_id");
                        String regionCode = rows.getString("region_code");
                        String categoryCode = rows.getString("category_code");
                        if (excludedPlaceIds.contains(placeId)) {
                            continue;
                        }
                        if (allowedPlaceIds != null && !allowedPlaceIds.contains(placeId)) {
                            continue;
                        }
                        if (allowedRegions != null && (regionCode == null || !allowedRegions.contains(regionCode))) {
                            continue;
                        }
                        if (allowedCategories != null && (categoryCode == null || !allowedCategories.contains(categoryCode))) {
                            continue;
                        }
                        eligible.add(placeId);
                    }
                }
            } finally {
                idsArray.free();
            }
            return Set.copyOf(eligible);
        } catch (SQLException exception) {
            throw new IllegalStateException("failed to verify public place eligibility", exception);
        }
    }
}
