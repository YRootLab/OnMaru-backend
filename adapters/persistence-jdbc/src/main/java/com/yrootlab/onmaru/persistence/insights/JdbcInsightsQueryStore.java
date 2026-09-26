package com.yrootlab.onmaru.persistence.insights;

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
        return List.of();
    }
}
