package com.yrootlab.onmaru.testing.postgres;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yrootlab.onmaru.persistence.catalog.JdbcTourApiCatalogPublisher;
import com.yrootlab.onmaru.tourism.catalog.TourApiCatalogSyncService;
import com.yrootlab.onmaru.tourism.catalog.client.TourApiClientProperties;
import com.yrootlab.onmaru.tourism.catalog.client.TourApiHttpClient;
import com.yrootlab.onmaru.tourism.catalog.client.TourApiUriBuilder;
import com.yrootlab.onmaru.tourism.catalog.sync.TourApiCatalogSnapshotSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.sql.Connection;
import java.time.Clock;

import static org.assertj.core.api.Assertions.assertThat;

/** Explicitly enabled production-data smoke; never runs in the ordinary test suite or CI. */
@EnabledIfEnvironmentVariable(named = "ONMARU_LIVE_TOURAPI_SYNC", matches = "true")
class TourApiLiveCatalogSyncTests {

    @Test
    void migratesAndPublishesACompleteOfficialSnapshot() throws Exception {
        String url = required("SPRING_DATASOURCE_URL");
        String username = required("SPRING_DATASOURCE_USERNAME");
        String password = required("SPRING_DATASOURCE_PASSWORD");
        var dataSource = new DriverManagerDataSource(url, username, password);

        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration/baseline")
                .load()
                .migrate();

        var source = new TourApiCatalogSnapshotSource(
                new TourApiHttpClient(new ObjectMapper(), TourApiClientProperties.defaults()),
                new TourApiUriBuilder(
                        java.net.URI.create("https://apis.data.go.kr/B551011/KorService2"),
                        required("ONMARU_SECRET_TOURAPI_SERVICE_KEY_CURRENT"),
                        "OnMaru"),
                1000);
        var service = new TourApiCatalogSyncService(
                source,
                new JdbcTourApiCatalogPublisher(dataSource),
                Clock.systemUTC());

        var result = service.syncFullSnapshot();
        assertThat(result.rawCount()).isGreaterThan(40_000);
        assertThat(result.rawCount()).isEqualTo(result.publishedCount() + result.quarantinedCount());

        try (Connection connection = dataSource.getConnection()) {
            assertThat(count(connection, """
                    SELECT count(*)
                    FROM onmaru.catalog_kto_korean_content_versions raw
                    JOIN onmaru.catalog_active_datasets active
                      ON active.dataset = 'kto-korean-tour' AND active.revision_id = raw.revision_id
                    """)).isEqualTo(result.rawCount());
            assertThat(count(connection, """
                    SELECT count(*)
                    FROM onmaru.catalog_place_versions place
                    JOIN onmaru.catalog_active_datasets active
                      ON active.dataset = 'kto-korean-tour' AND active.revision_id = place.revision_id
                    """)).isEqualTo(result.publishedCount());
        }

        System.out.printf("LIVE_TOURAPI_SYNC revision=%s raw=%d published=%d quarantined=%d%n",
                result.revisionId(), result.rawCount(), result.publishedCount(), result.quarantinedCount());
    }

    private long count(Connection connection, String sql) throws Exception {
        try (var statement = connection.createStatement(); var rows = statement.executeQuery(sql)) {
            rows.next();
            return rows.getLong(1);
        }
    }

    private String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException(name + " is required");
        return value;
    }
}
