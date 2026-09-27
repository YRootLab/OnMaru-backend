package com.yrootlab.onmaru.persistence.insights;

import com.yrootlab.onmaru.insights.query.Coordinates;
import com.yrootlab.onmaru.insights.query.HeatSpot;
import com.yrootlab.onmaru.insights.query.InsightsQueryStore;
import com.yrootlab.onmaru.insights.query.Observation;
import com.yrootlab.onmaru.insights.query.RegionRef;

import javax.sql.DataSource;
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
                WITH active_observations AS (
                    SELECT observation.*, region.code, region.name, region.level::text AS level,
                           parent.code AS parent_code,
                           MAX(observation.visitor_count) OVER (PARTITION BY observation.basis_date) AS max_count
                    FROM onmaru.catalog_active_datasets active
                    JOIN onmaru.insights_visitor_observations observation
                      ON observation.revision_id = active.revision_id
                    JOIN onmaru.catalog_regions region ON region.id = observation.region_id
                    LEFT JOIN onmaru.catalog_regions parent ON parent.id = region.parent_id
                    WHERE active.dataset = 'kto-datalab-visitor'
                      AND region.level = 'SIGUNGU'
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
                Double score = visitorCount == null || maxCount == null || maxCount == 0
                        ? null
                        : Math.round(visitorCount * 10_000.0 / maxCount) / 100.0;
                items.add(new HeatSpot(
                        "heat-region-" + result.getString("code") + "-" + result.getObject("basis_date"),
                        "region:" + result.getString("code"),
                        result.getString("name"),
                        new RegionRef(result.getString("code"), result.getString("name"),
                                result.getString("level"), result.getString("parent_code")),
                        new Coordinates(result.getDouble("latitude"), result.getDouble("longitude")),
                        visitorCount,
                        score,
                        score == null ? null : congestionLevel(score),
                        1.0,
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
}
