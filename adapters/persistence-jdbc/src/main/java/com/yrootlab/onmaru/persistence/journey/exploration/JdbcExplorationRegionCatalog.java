package com.yrootlab.onmaru.persistence.journey.exploration;

import com.yrootlab.onmaru.journey.exploration.ExplorationRegionCatalog;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public final class JdbcExplorationRegionCatalog implements ExplorationRegionCatalog {

    private static final Duration DEFAULT_TTL = Duration.ofMinutes(5);
    private static final String QUERY = """
            SELECT child.code, child.name, child.level::text AS level, parent.name AS parent_name
            FROM onmaru.catalog_regions child
            LEFT JOIN onmaru.catalog_regions parent ON child.parent_id = parent.id
            WHERE child.active = true
            ORDER BY child.level, child.code
            """;

    private record CachedCatalog(ExplorationRegionCatalog delegate, Instant expiresAt) {}

    private final DataSource dataSource;
    private final Clock clock;
    private final Duration ttl;
    private volatile CachedCatalog cached;

    public JdbcExplorationRegionCatalog(DataSource dataSource, Clock clock) {
        this(dataSource, clock, DEFAULT_TTL);
    }

    public JdbcExplorationRegionCatalog(DataSource dataSource, Clock clock, Duration ttl) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.ttl = Objects.requireNonNull(ttl, "ttl");
    }

    @Override
    public Optional<String> resolveRegionCode(String normalizedQuery) {
        return currentCatalog().resolveRegionCode(normalizedQuery);
    }

    public void invalidate() {
        this.cached = null;
    }

    private ExplorationRegionCatalog currentCatalog() {
        var now = clock.instant();
        var current = this.cached;
        if (current != null && now.isBefore(current.expiresAt())) {
            return current.delegate();
        }
        synchronized (this) {
            current = this.cached;
            now = clock.instant();
            if (current != null && now.isBefore(current.expiresAt())) {
                return current.delegate();
            }
            var loaded = loadCatalog();
            this.cached = new CachedCatalog(loaded, now.plus(ttl));
            return loaded;
        }
    }

    private ExplorationRegionCatalog loadCatalog() {
        List<RegionEntry> entries = new ArrayList<>();
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement(QUERY);
             var rs = statement.executeQuery()) {
            while (rs.next()) {
                entries.add(new RegionEntry(
                        rs.getString("code"),
                        rs.getString("name"),
                        rs.getString("level"),
                        rs.getString("parent_name")));
            }
        } catch (SQLException exception) {
            throw new RuntimeException("Failed to load active exploration regions", exception);
        }
        return ExplorationRegionCatalog.fromEntries(entries);
    }
}
