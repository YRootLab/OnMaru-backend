package com.yrootlab.onmaru.persistence.insights;

import com.yrootlab.onmaru.insights.query.Coordinates;
import com.yrootlab.onmaru.insights.query.HeatSpot;
import com.yrootlab.onmaru.insights.query.InsightsQueryStore;
import com.yrootlab.onmaru.insights.query.Observation;
import com.yrootlab.onmaru.insights.query.RegionRef;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/** Reads only the currently active, published visitor snapshot. */
public final class JdbcInsightsQueryStore implements InsightsQueryStore {
    private final DataSource dataSource;

    public JdbcInsightsQueryStore(DataSource dataSource) { this.dataSource = dataSource; }

    @Override
    public List<Observation> observations() {
        try (var connection = dataSource.getConnection(); var statement = connection.prepareStatement("""
                SELECT observation.revision_id, region.code, region.name, region.level::text,
                       parent.code AS parent_code, observation.basis_date,
                       observation.visitor_count, observation.spatial_level,
                       observation.coverage_status
                FROM onmaru.catalog_active_datasets active
                JOIN onmaru.insights_visitor_observations observation ON observation.revision_id=active.revision_id
                JOIN onmaru.catalog_regions region ON region.id=observation.region_id
                LEFT JOIN onmaru.catalog_regions parent ON parent.id=region.parent_id
                WHERE active.dataset='kto-datalab-visitor'
                ORDER BY observation.basis_date DESC, region.code
                """); var result = statement.executeQuery()) {
            var items = new ArrayList<Observation>();
            while (result.next()) items.add(new Observation(
                    "visitor-" + result.getString("revision_id") + "-" + result.getString("code") + "-" + result.getObject("basis_date"),
                    new RegionRef(result.getString("code"), result.getString("name"), result.getString("level"), result.getString("parent_code")),
                    result.getObject("basis_date", java.time.LocalDate.class), "VISITOR_COUNT",
                    result.getObject("visitor_count", Long.class), "persons", result.getString("spatial_level"),
                    result.getString("coverage_status")));
            return List.copyOf(items);
        } catch (Exception exception) { throw new IllegalStateException("Failed to query active insights observations", exception); }
    }

    @Override
    public List<HeatSpot> heatSpots() {
        try (var connection = dataSource.getConnection(); var statement = connection.prepareStatement("""
                WITH regional_observations AS (
                    SELECT observation.revision_id, observation.region_id, observation.basis_date,
                           MAX(observation.visitor_count) FILTER (WHERE observation.visitor_type IN ('2', 'DOMESTIC', 'TOTAL')) AS visitor_count,
                           AVG(observation.visitor_count) FILTER (WHERE observation.visitor_type IN ('1', 'LOCAL')) AS local_count,
                           MAX(observation.coverage_status) AS coverage_status,
                           region.code, region.name, region.level::text AS level,
                           parent.code AS parent_code,
                           region.id AS catalog_region_id
                    FROM onmaru.catalog_active_datasets active
                    JOIN onmaru.insights_visitor_observations observation
                      ON observation.revision_id = active.revision_id
                    JOIN onmaru.catalog_regions region ON region.id = observation.region_id
                    LEFT JOIN onmaru.catalog_regions parent ON parent.id = region.parent_id
                    WHERE active.dataset = 'kto-datalab-visitor'
                      AND region.level = 'SIGUNGU'
                    GROUP BY observation.revision_id, observation.region_id, observation.basis_date,
                             region.id, region.code, region.name, region.level, parent.code
                ), active_observations AS (
                    SELECT regional_observations.*,
                           MAX(visitor_count) OVER () AS max_count,
                           AVG(local_count) OVER (PARTITION BY catalog_region_id) AS regional_local_count
                    FROM regional_observations
                )
                SELECT observation.*, center.latitude, center.longitude
                FROM active_observations observation
                JOIN LATERAL (
                    SELECT AVG(ST_Y(version.location::geometry)) AS latitude,
                           AVG(ST_X(version.location::geometry)) AS longitude
                    FROM onmaru.catalog_active_datasets catalog_active
                    JOIN onmaru.catalog_place_versions version
                      ON version.revision_id = catalog_active.revision_id
                    WHERE catalog_active.dataset = 'kto-korean-tour'
                      AND version.status = 'ACTIVE'
                      AND version.location IS NOT NULL
                      AND (version.region_id = observation.region_id
                           OR version.address LIKE '%' || observation.name || '%')
                ) center ON center.latitude IS NOT NULL AND center.longitude IS NOT NULL
                ORDER BY observation.basis_date DESC, observation.code
                """); var result = statement.executeQuery()) {
            var items = new ArrayList<HeatSpot>();
            while (result.next()) {
                Long visitorCount = result.getObject("visitor_count", Long.class);
                Long maxCount = result.getObject("max_count", Long.class);
                BigDecimal localCountValue = result.getObject("regional_local_count", BigDecimal.class);
                Long localCount = localCountValue == null ? null : localCountValue.longValue();
                double baseline = localCount == null ? 120_000.0 : localCount;
                Double score = visitorCount == null || maxCount == null || maxCount == 0
                        ? null
                        : score(visitorCount, baseline, maxCount);
                Double surgeMultiplier = score == null ? null : surge(visitorCount, baseline);
                items.add(new HeatSpot(
                        "heat-region-" + result.getString("code") + "-" + result.getObject("basis_date"),
                        "region:" + result.getString("code"),
                        result.getString("name"),
                        new RegionRef(result.getString("code"), result.getString("name"),
                                result.getString("level"), result.getString("parent_code")),
                        new Coordinates(result.getDouble("latitude"), result.getDouble("longitude")),
                        visitorCount, localCount,
                        score,
                        score == null ? null : congestionLevel(score),
                        surgeMultiplier,
                        result.getString("coverage_status"),
                        result.getObject("basis_date", java.time.LocalDate.class),
                        "CONGESTION_SCORE"));
            }
            return List.copyOf(items);
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to query active insights heatmap", exception);
        }
    }

    private String congestionLevel(double score) {
        if (score >= 80.0) return "SURGE";
        if (score >= 55.0) return "BUSY";
        if (score >= 30.0) return "MODERATE";
        return "RELAXED";
    }

    private double score(long visitorCount, double localCount, long maxVisitor) {
        double ratio = visitorCount / Math.max(localCount, 15_000.0);
        double concentration = ratio / (ratio + 1.0) * 100.0;
        double volume = Math.min(100.0, visitorCount * 100.0 / Math.max(maxVisitor, 1L));
        return Math.round(Math.min(100.0, concentration * 0.45 + volume * 0.55));
    }

    private double surge(long visitorCount, double localCount) {
        double ratio = visitorCount / Math.max(localCount, 15_000.0);
        return Math.round(Math.min(3.5, Math.max(1.0, 1.0 + ratio * 0.75)) * 10.0) / 10.0;
    }
}
