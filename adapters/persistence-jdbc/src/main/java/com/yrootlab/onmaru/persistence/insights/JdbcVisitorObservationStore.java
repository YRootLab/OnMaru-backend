package com.yrootlab.onmaru.persistence.insights;

import com.yrootlab.onmaru.insights.observation.ObservationMetric;
import com.yrootlab.onmaru.insights.observation.VisitorObservation;
import com.yrootlab.onmaru.insights.observation.VisitorObservationStore;
import com.yrootlab.onmaru.community.query.RegionVisitorCountLookup;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** PostgreSQL store for ADR-0015's active DataLab visitor snapshot. */
public final class JdbcVisitorObservationStore implements VisitorObservationStore, RegionVisitorCountLookup {

    static final String DATASET = "kto-datalab-visitor";

    private final DataSource dataSource;

    public JdbcVisitorObservationStore(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public void save(UUID revisionId, VisitorObservation observation) {
        requireVisitorCount(observation);
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement("""
                     INSERT INTO onmaru.insights_visitor_observations (
                         revision_id, region_id, basis_date, visitor_type, provider, spatial_level,
                         visitor_count, coverage_status, source_observed_at, fetched_at
                     )
                     SELECT ?, region.id, ?, 'TOTAL', ?, ?, ?, ?, ?, now()
                     FROM onmaru.catalog_regions region
                     WHERE region.code = ? AND region.active
                     ON CONFLICT (revision_id, region_id, basis_date, visitor_type) DO UPDATE
                     SET provider = EXCLUDED.provider,
                         spatial_level = EXCLUDED.spatial_level,
                         visitor_count = EXCLUDED.visitor_count,
                         coverage_status = EXCLUDED.coverage_status,
                         source_observed_at = EXCLUDED.source_observed_at,
                         fetched_at = EXCLUDED.fetched_at
                     """)) {
            statement.setObject(1, revisionId);
            statement.setObject(2, observation.basisDate());
            statement.setString(3, observation.provider());
            statement.setString(4, observation.spatialLevel().name());
            if (observation.value() == null) {
                statement.setNull(5, java.sql.Types.BIGINT);
            } else {
                statement.setLong(5, observation.value());
            }
            statement.setString(6, observation.coverageStatus().name());
            statement.setObject(7, OffsetDateTime.ofInstant(observation.sourceObservedAt(), ZoneOffset.UTC));
            statement.setString(8, observation.regionCode());
            if (statement.executeUpdate() != 1) {
                throw new IllegalArgumentException("No active Catalog region for DataLab regionCode: "
                        + observation.regionCode());
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed to persist DataLab visitor observation", exception);
        }
    }

    @Override
    public Map<String, Long> findLatestCompleteByRegionCodes(Set<String> regionCodes) {
        if (regionCodes == null || regionCodes.isEmpty()) {
            return Map.of();
        }
        String placeholders = String.join(",", java.util.Collections.nCopies(regionCodes.size(), "?"));
        String sql = """
                SELECT DISTINCT ON (region.code) region.code, observation.visitor_count
                FROM onmaru.insights_visitor_observations observation
                JOIN onmaru.catalog_regions region ON region.id = observation.region_id
                JOIN onmaru.catalog_active_datasets active
                  ON active.dataset = ? AND active.revision_id = observation.revision_id
                WHERE observation.coverage_status = 'COMPLETE'
                  AND observation.visitor_count IS NOT NULL
                  AND region.code IN (%s)
                ORDER BY region.code, observation.basis_date DESC, observation.fetched_at DESC
                """.formatted(placeholders);
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement(sql)) {
            statement.setString(1, DATASET);
            int index = 2;
            for (String regionCode : regionCodes) {
                statement.setString(index++, regionCode);
            }
            try (var result = statement.executeQuery()) {
                var counts = new LinkedHashMap<String, Long>();
                while (result.next()) {
                    counts.put(result.getString("code"), result.getLong("visitor_count"));
                }
                return Map.copyOf(counts);
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed to query DataLab visitor observations", exception);
        }
    }

    @Override
    public Map<String, Long> findLatestVisitorCounts(Set<String> regionCodes) {
        return findLatestCompleteByRegionCodes(regionCodes);
    }

    private void requireVisitorCount(VisitorObservation observation) {
        if (observation == null || observation.metric() != ObservationMetric.VISITOR_COUNT) {
            throw new IllegalArgumentException("Only VISITOR_COUNT observations may be stored here");
        }
        if (!"persons".equals(observation.unit()) || observation.spatialLevel().name().equals("UNKNOWN")) {
            throw new IllegalArgumentException("DataLab visitor observation has invalid unit or spatial level");
        }
        if (observation.value() != null && observation.value() < 0) {
            throw new IllegalArgumentException("DataLab visitor count must not be negative");
        }
    }
}
