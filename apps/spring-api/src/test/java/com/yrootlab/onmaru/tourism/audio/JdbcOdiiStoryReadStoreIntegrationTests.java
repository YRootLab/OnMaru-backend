package com.yrootlab.onmaru.tourism.audio;

import com.yrootlab.onmaru.audio.query.OdiiProjectionMetadata;
import com.yrootlab.onmaru.audio.query.OdiiRegionRef;
import com.yrootlab.onmaru.testing.postgres.PostgresTestDatabase;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.time.Instant;
import java.util.UUID;
import java.util.logging.Logger;

import static org.assertj.core.api.Assertions.assertThat;

class JdbcOdiiStoryReadStoreIntegrationTests {

    private static final int POSTGRES_PORT = 5432;
    private static final String DATABASE = "onmaru_test";
    private static final String USERNAME = "onmaru_test";
    private static final String PASSWORD = "onmaru_test";
    private static final String DATASET = "odii-read-test";
    private static final UUID REVISION_ID = UUID.fromString("40000000-0000-0000-0000-000000000001");

    private static final GenericContainer<?> postgres = new GenericContainer<>(
            DockerImageName.parse("postgis/postgis:17-3.5-alpine"))
            .withExposedPorts(POSTGRES_PORT)
            .withEnv("POSTGRES_DB", DATABASE)
            .withEnv("POSTGRES_USER", USERNAME)
            .withEnv("POSTGRES_PASSWORD", PASSWORD)
            .waitingFor(Wait.forLogMessage(".*database system is ready to accept connections.*\\n", 2));

    @BeforeAll
    static void startPostgres() throws Exception {
        postgres.start();
        try (var connection = dataSource().getConnection()) {
            PostgresTestDatabase.reset(connection);
        }
        Flyway.configure()
                .dataSource(jdbcUrl(), USERNAME, PASSWORD)
                .locations("classpath:db/migration/baseline")
                .baselineOnMigrate(true)
                .baselineVersion("0")
                .load()
                .migrate();
        seedPublishedStories();
    }

    @AfterAll
    static void stopPostgres() {
        postgres.stop();
    }

    @Test
    void loadsOnlyTheRequestedStoryAndItsSubtitleLines() {
        var store = readStore();
        String storyId = publicStoryId("KTO_ODII", "story-2");

        var selection = store.detail(storyId, "ko-KR");

        assertThat(selection.revisionId()).isEqualTo(REVISION_ID);
        assertThat(selection.language()).isEqualTo("ko-KR");
        assertThat(selection.stories()).singleElement().satisfies(story -> {
            assertThat(story.storyId()).isEqualTo(storyId);
            assertThat(story.audioTitle()).isEqualTo("이야기 2");
            assertThat(story.transcript()).extracting(line -> line.text())
                    .containsExactly("두 번째 이야기의 첫 문장", "두 번째 이야기의 두 번째 문장");
        });
    }

    @Test
    void appliesLanguageOrderingAndLimitInsidePostgresForTheListPage() {
        var store = readStore();

        var page = store.list("ko-KR", 2, null, null);

        assertThat(page.revisionId()).isEqualTo(REVISION_ID);
        assertThat(page.language()).isEqualTo("ko-KR");
        assertThat(page.hasMore()).isTrue();
        assertThat(page.stories()).extracting(story -> story.audioTitle())
                .containsExactly("이야기 3", "이야기 2");
        assertThat(page.stories()).allSatisfy(story -> assertThat(story.transcript()).isEmpty());
    }

    @Test
    void filtersSearchAndNearbyCandidatesInsidePostgres() {
        var store = readStore();

        var search = store.search("이야기 2", "ko-KR", 10, true);
        var emptySearch = store.search("존재하지 않는 검색어", "ko-KR", 10, false);
        var nearby = store.nearby(37.0, 127.01, 1_500, "ko-KR", 10);

        assertThat(search.stories()).extracting(story -> story.audioTitle())
                .containsExactly("이야기 2");
        assertThat(nearby.stories()).extracting(story -> story.audioTitle())
                .containsExactly("이야기 1", "이야기 2");
        assertThat(emptySearch.languageStatus()).isEqualTo(com.yrootlab.onmaru.audio.query.OdiiLanguageStatus.EXACT);
        assertThat(emptySearch.stories()).isEmpty();
        assertThat(search.stories()).allSatisfy(story -> assertThat(story.transcript()).isEmpty());
        assertThat(nearby.stories()).allSatisfy(story -> assertThat(story.transcript()).isEmpty());
    }

    @Test
    void aggregatesAndLimitsPopularityInsidePostgres() {
        var selection = readStore().popular(
                "ko-KR", null, Instant.parse("2026-09-26T00:00:00Z"), 2);

        assertThat(selection.hasSignal()).isTrue();
        assertThat(selection.candidates()).hasSize(2);
        assertThat(selection.candidates().getFirst().story().audioTitle()).isEqualTo("이야기 1");
        assertThat(selection.candidates().getFirst().playCount()).isEqualTo(2);
        assertThat(selection.candidates().getFirst().score()).isEqualTo(4);
    }

    private JdbcOdiiStoryReadStore readStore() {
        return new JdbcOdiiStoryReadStore(
                dataSource(),
                DATASET,
                "오디오 관광",
                java.util.Set.of("sfj608538-sfj608538.ktcdn.co.kr"),
                ignored -> new OdiiProjectionMetadata(
                        "오디오 관광",
                        new OdiiRegionRef("kr", "대한민국", "COUNTRY", null)));
    }

    private static String publicStoryId(String provider, String stid) {
        return "odii-story-" + UUID.nameUUIDFromBytes(
                (provider + ":odii-story-:" + stid).getBytes(StandardCharsets.UTF_8));
    }

    private static void seedPublishedStories() throws SQLException {
        try (var connection = dataSource().getConnection();
             var statement = connection.createStatement()) {
            statement.execute("""
                    INSERT INTO onmaru.catalog_dataset_revisions (
                        id, dataset, status, source_observed_at, fetched_at, published_at
                    ) VALUES (
                        '%s', '%s', 'PUBLISHED', CURRENT_TIMESTAMP,
                        CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
                    )
                    """.formatted(REVISION_ID, DATASET));
            statement.execute("""
                    INSERT INTO onmaru.catalog_active_datasets (dataset, revision_id, activated_at)
                    VALUES ('%s', '%s', CURRENT_TIMESTAMP)
                    """.formatted(DATASET, REVISION_ID));
            statement.execute("""
                    INSERT INTO onmaru.audio_odii_spots (
                        id, provider, tid, tlid, lang_code, created_at
                    )
                    SELECT md5('read-spot-' || value)::uuid,
                           'KTO_ODII', 'spot-' || value, 'spot-local-' || value,
                           'ko', CURRENT_TIMESTAMP
                    FROM generate_series(1, 3) AS value
                    """);
            statement.execute("""
                    INSERT INTO onmaru.audio_spot_versions (
                        revision_id, spot_id, title, location, status, hash, source_modified_at
                    )
                    SELECT '%s', md5('read-spot-' || value)::uuid, '장소 ' || value,
                           ST_SetSRID(ST_MakePoint(127.0 + value / 100.0, 37.0), 4326)::geography,
                           'ACTIVE', 'spot-hash-' || value,
                           '2026-09-27T00:00:00Z'::timestamptz + value * INTERVAL '1 minute'
                    FROM generate_series(1, 3) AS value
                    """.formatted(REVISION_ID));
            statement.execute("""
                    INSERT INTO onmaru.audio_odii_stories (
                        id, spot_id, provider, stid, stlid, lang_code, created_at
                    )
                    SELECT md5('read-story-' || value)::uuid,
                           md5('read-spot-' || value)::uuid,
                           'KTO_ODII', 'story-' || value, 'story-local-' || value,
                           'ko', CURRENT_TIMESTAMP
                    FROM generate_series(1, 3) AS value
                    """);
            statement.execute("""
                    INSERT INTO onmaru.audio_story_versions (
                        revision_id, story_id, spot_id, title, script, audio_url, image_url,
                        duration_seconds, status, hash, transcript_provenance, source_modified_at
                    )
                    SELECT '%s', md5('read-story-' || value)::uuid,
                           md5('read-spot-' || value)::uuid, '이야기 ' || value,
                           '본문 ' || value,
                           'https://sfj608538-sfj608538.ktcdn.co.kr/audio-' || value || '.mp3',
                           'https://sfj608538-sfj608538.ktcdn.co.kr/image-' || value || '.jpg',
                           60 + value, 'ACTIVE', 'story-hash-' || value, 'OFFICIAL',
                           '2026-09-27T00:00:00Z'::timestamptz + value * INTERVAL '1 minute'
                    FROM generate_series(1, 3) AS value
                    """.formatted(REVISION_ID));
            statement.execute("""
                    INSERT INTO onmaru.audio_subtitle_lines (
                        revision_id, story_id, position, text, start_seconds, timing_mode
                    )
                    SELECT '%s'::uuid, id, 0, '두 번째 이야기의 첫 문장', 0, 'OFFICIAL'
                    FROM onmaru.audio_odii_stories WHERE stid = 'story-2'
                    UNION ALL
                    SELECT '%s'::uuid, id, 1, '두 번째 이야기의 두 번째 문장', 3.5, 'OFFICIAL'
                    FROM onmaru.audio_odii_stories WHERE stid = 'story-2'
                    """.formatted(REVISION_ID, REVISION_ID));
            statement.execute("""
                    INSERT INTO onmaru.audio_story_play_events (id, story_id, occurred_at)
                    VALUES
                        (gen_random_uuid(), '%s', '2026-09-27T00:00:00Z'),
                        (gen_random_uuid(), '%s', '2026-09-27T00:01:00Z')
                    """.formatted(
                    publicStoryId("KTO_ODII", "story-1"),
                    publicStoryId("KTO_ODII", "story-1")));
        }
    }

    private static DataSource dataSource() {
        return new DriverManagerDataSource(jdbcUrl(), USERNAME, PASSWORD);
    }

    private static String jdbcUrl() {
        return "jdbc:postgresql://%s:%d/%s".formatted(
                postgres.getHost(), postgres.getMappedPort(POSTGRES_PORT), DATABASE);
    }

    private record DriverManagerDataSource(String url, String username, String password) implements DataSource {
        @Override public Connection getConnection() throws SQLException {
            SQLException lastFailure = null;
            for (int attempt = 0; attempt < 20; attempt++) {
                try {
                    return DriverManager.getConnection(url, username, password);
                } catch (SQLException exception) {
                    lastFailure = exception;
                    try {
                        Thread.sleep(100);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new SQLException("interrupted while waiting for PostgreSQL", interrupted);
                    }
                }
            }
            throw lastFailure;
        }
        @Override public Connection getConnection(String username, String password) throws SQLException {
            return DriverManager.getConnection(url, username, password);
        }
        @Override public PrintWriter getLogWriter() { return null; }
        @Override public void setLogWriter(PrintWriter out) { }
        @Override public void setLoginTimeout(int seconds) { DriverManager.setLoginTimeout(seconds); }
        @Override public int getLoginTimeout() { return DriverManager.getLoginTimeout(); }
        @Override public Logger getParentLogger() throws SQLFeatureNotSupportedException {
            return Logger.getLogger(Logger.GLOBAL_LOGGER_NAME);
        }
        @Override public <T> T unwrap(Class<T> iface) throws SQLException { throw new SQLException("not a wrapper"); }
        @Override public boolean isWrapperFor(Class<?> iface) { return false; }
    }
}
