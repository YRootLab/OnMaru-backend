package com.yrootlab.onmaru.testing.postgres;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AudioMigrationTests {

    private static final int POSTGRES_PORT = 5432;
    private static final String DATABASE = "onmaru_test";
    private static final String USERNAME = "onmaru_test";
    private static final String PASSWORD = "onmaru_test";

    private static final GenericContainer<?> postgres = new GenericContainer<>(
            DockerImageName.parse("postgis/postgis:17-3.5-alpine"))
            .withExposedPorts(POSTGRES_PORT)
            .withEnv("POSTGRES_DB", DATABASE)
            .withEnv("POSTGRES_USER", USERNAME)
            .withEnv("POSTGRES_PASSWORD", PASSWORD)
            .waitingFor(Wait.forLogMessage(".*database system is ready to accept connections.*\\n", 2));

    @BeforeAll
    static void startPostgres() {
        postgres.start();
    }

    @AfterAll
    static void stopPostgres() {
        postgres.stop();
    }

    @Test
    void migratesOdiiAudioIdentityVersionTranscriptAndLinkSchema() throws Exception {
        resetAndMigrate();

        try (var connection = DriverManager.getConnection(jdbcUrl(), USERNAME, PASSWORD);
             var statement = connection.createStatement()) {
            assertThat(countRows(statement, """
                    SELECT COUNT(*)
                    FROM information_schema.tables
                    WHERE table_schema = 'onmaru'
                      AND table_name IN (
                        'audio_odii_spots',
                        'audio_odii_stories',
                        'audio_spot_versions',
                        'audio_story_versions',
                        'audio_subtitle_lines',
                        'audio_place_odii_links'
                      )
                    """)).isEqualTo(6);
            assertThat(countRows(statement, """
                    SELECT COUNT(*)
                    FROM pg_indexes
                    WHERE schemaname = 'onmaru'
                      AND indexname = 'audio_spot_versions_location_gix'
                      AND indexdef ILIKE '%gist%'
                    """)).isEqualTo(1);
        }
    }

    @Test
    void separatesLanguageIdsAndRejectsProviderCollisions() throws Exception {
        resetAndMigrate();
        var koreanSpot = UUID.randomUUID();
        var englishSpot = UUID.randomUUID();
        var koreanStory = UUID.randomUUID();
        var englishStory = UUID.randomUUID();

        try (var connection = DriverManager.getConnection(jdbcUrl(), USERNAME, PASSWORD);
             var statement = connection.createStatement()) {
            insertSpot(statement, koreanSpot, "KTO_ODII", "tid-1", "tlid-ko", "ko");
            insertSpot(statement, englishSpot, "KTO_ODII", "tid-1", "tlid-en", "en");
            assertThat(countRows(statement, "SELECT COUNT(*) FROM onmaru.audio_odii_spots"))
                    .isEqualTo(2);

            assertThatThrownBy(() -> insertSpot(
                    statement,
                    UUID.randomUUID(),
                    "KTO_ODII",
                    "tid-1",
                    "tlid-ko",
                    "ko"
            ))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("audio_odii_spots_provider_tid_tlid_uq");

            insertStory(statement, koreanStory, koreanSpot, "KTO_ODII", "stid-1", "stlid-ko", "ko");
            insertStory(statement, englishStory, koreanSpot, "KTO_ODII", "stid-1", "stlid-en", "en");
            assertThat(countRows(statement, "SELECT COUNT(*) FROM onmaru.audio_odii_stories"))
                    .isEqualTo(2);

            assertThatThrownBy(() -> insertStory(
                    statement,
                    UUID.randomUUID(),
                    englishSpot,
                    "KTO_ODII",
                    "stid-1",
                    "stlid-ko",
                    "ko"
            ))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("audio_odii_stories_provider_stid_stlid_uq");
        }
    }

    @Test
    void enforcesRevisionForeignKeysTombstoneAndTranscriptRules() throws Exception {
        resetAndMigrate();
        var revisionId = UUID.randomUUID();
        var spotId = UUID.randomUUID();
        var storyId = UUID.randomUUID();

        try (var connection = DriverManager.getConnection(jdbcUrl(), USERNAME, PASSWORD);
             var statement = connection.createStatement()) {
            insertRevision(statement, revisionId, "odii-audio");
            insertSpot(statement, spotId, "KTO_ODII", "tid-1", "tlid-ko", "ko");
            insertStory(statement, storyId, spotId, "KTO_ODII", "stid-1", "stlid-ko", "ko");
            insertSpotVersion(statement, revisionId, spotId, "전주 한옥마을 오디오", "ACTIVE");
            insertStoryVersion(statement, revisionId, storyId, spotId, "한옥마을 이야기", "ACTIVE", 120);
            insertSubtitleLine(statement, revisionId, storyId, 0, "전주 한옥마을을 걷습니다.", 0);

            assertThat(countRows(statement, """
                    SELECT COUNT(*)
                    FROM onmaru.audio_story_versions
                    WHERE revision_id = '%s'
                      AND story_id = '%s'
                    """.formatted(revisionId, storyId))).isEqualTo(1);

            assertThatThrownBy(() -> insertStoryVersion(
                    statement,
                    UUID.randomUUID(),
                    storyId,
                    spotId,
                    "고아 revision",
                    "ACTIVE",
                    10
            ))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("audio_story_versions_revision_id_fkey");

            assertThatThrownBy(() -> insertStoryVersion(
                    statement,
                    revisionId,
                    UUID.randomUUID(),
                    spotId,
                    "없는 story",
                    "DELETED",
                    null
            ))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("audio_story_versions_story_id_fkey");

            assertThatThrownBy(() -> insertSubtitleLine(statement, revisionId, storyId, 1, "음수 시작", -1))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("audio_subtitle_lines_start_seconds_ck");
        }
    }

    @Test
    void enforcesVerifiedPlaceLinksAndConfidenceRange() throws Exception {
        resetAndMigrate();
        var placeId = UUID.randomUUID();
        var spotId = UUID.randomUUID();

        try (var connection = DriverManager.getConnection(jdbcUrl(), USERNAME, PASSWORD);
             var statement = connection.createStatement()) {
            statement.execute("""
                    INSERT INTO onmaru.catalog_place_identity (id, created_at)
                    VALUES ('%s', CURRENT_TIMESTAMP)
                    """.formatted(placeId));
            insertSpot(statement, spotId, "KTO_ODII", "tid-1", "tlid-ko", "ko");

            statement.execute("""
                    INSERT INTO onmaru.audio_place_odii_links (
                        place_id, spot_id, match_method, confidence, review_status, verified_at
                    ) VALUES (
                        '%s', '%s', 'MANUAL_VERIFIED', 0.95, 'APPROVED', CURRENT_TIMESTAMP
                    )
                    """.formatted(placeId, spotId));

            assertThatThrownBy(() -> statement.execute("""
                    INSERT INTO onmaru.audio_place_odii_links (
                        place_id, spot_id, match_method, confidence, review_status, verified_at
                    ) VALUES (
                        '%s', '%s', 'NAME_DISTANCE_ONLY', 1.2, 'PENDING', NULL
                    )
                    """.formatted(placeId, spotId)))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("audio_place_odii_links_confidence_ck");
        }
    }

    @Test
    void permitsOnlyOneApprovedPlaceLinkPerAudioSpot() throws Exception {
        resetAndMigrate();
        var firstPlaceId = UUID.randomUUID();
        var secondPlaceId = UUID.randomUUID();
        var spotId = UUID.randomUUID();

        try (var connection = DriverManager.getConnection(jdbcUrl(), USERNAME, PASSWORD);
             var statement = connection.createStatement()) {
            statement.execute("""
                    INSERT INTO onmaru.catalog_place_identity (id, created_at)
                    VALUES ('%s', CURRENT_TIMESTAMP), ('%s', CURRENT_TIMESTAMP)
                    """.formatted(firstPlaceId, secondPlaceId));
            insertSpot(statement, spotId, "KTO_ODII", "tid-approved", "tlid-approved", "ko");
            insertPlaceLink(statement, firstPlaceId, spotId, "APPROVED");

            assertThatThrownBy(() -> insertPlaceLink(statement, secondPlaceId, spotId, "APPROVED"))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("audio_place_odii_links_one_approved_per_spot_uq");
        }
    }

    @Test
    void publishesSpotStoryPointerAndWatermarkInOneDatabaseTransaction() throws Exception {
        resetAndMigrate();
        var baseRevision = UUID.randomUUID();
        var stagedRevision = UUID.randomUUID();
        var spotId = UUID.randomUUID();
        var storyId = UUID.randomUUID();

        try (var connection = DriverManager.getConnection(jdbcUrl(), USERNAME, PASSWORD);
             var statement = connection.createStatement()) {
            insertRevision(statement, baseRevision, "odii-audio");
            statement.execute("""
                    UPDATE onmaru.catalog_dataset_revisions
                    SET status = 'PUBLISHED', published_at = CURRENT_TIMESTAMP
                    WHERE id = '%s'
                    """.formatted(baseRevision));
            insertRevision(statement, stagedRevision, "odii-audio");
            statement.execute("""
                    UPDATE onmaru.catalog_dataset_revisions
                    SET status = 'READY'
                    WHERE id = '%s'
                    """.formatted(stagedRevision));
            insertSpot(statement, spotId, "KTO_ODII", "89", "300", "ko");
            insertStory(statement, storyId, spotId, "KTO_ODII", "562", "1204", "ko");
            insertSpotVersion(statement, stagedRevision, spotId, "남산골 한옥마을", "ACTIVE");
            insertStoryVersion(statement, stagedRevision, storyId, spotId, "한옥마을 개요", "ACTIVE", 105);
            statement.execute("""
                    INSERT INTO onmaru.catalog_active_datasets (dataset, revision_id, activated_at)
                    VALUES ('odii-audio', '%s', CURRENT_TIMESTAMP)
                    """.formatted(baseRevision));
            statement.execute("""
                    INSERT INTO onmaru.operations_sync_leases (dataset, owner_token, generation, lease_until)
                    VALUES ('odii-audio', 'worker-a', 1, CURRENT_TIMESTAMP + INTERVAL '5 minutes')
                    """);
            statement.execute("""
                    INSERT INTO onmaru.operations_sync_watermarks (
                        dataset, source_modified_at, external_id, last_full_success_at,
                        last_success_at, revision_id
                    ) VALUES (
                        'odii-audio', '2025-06-08T07:46:06Z', '1200', CURRENT_TIMESTAMP,
                        CURRENT_TIMESTAMP, '%s'
                    )
                    """.formatted(baseRevision));

            connection.setAutoCommit(false);
            assertThat(activateAudioRevision(statement, baseRevision, stagedRevision)).isEqualTo(1);
            updateAudioWatermark(statement, stagedRevision);
            connection.rollback();

            assertThat(singleUuid(statement, """
                    SELECT revision_id FROM onmaru.catalog_active_datasets WHERE dataset = 'odii-audio'
                    """)).isEqualTo(baseRevision);
            assertThat(singleUuid(statement, """
                    SELECT revision_id FROM onmaru.operations_sync_watermarks WHERE dataset = 'odii-audio'
                    """)).isEqualTo(baseRevision);

            assertThat(activateAudioRevision(statement, baseRevision, stagedRevision)).isEqualTo(1);
            updateAudioWatermark(statement, stagedRevision);
            statement.execute("""
                    UPDATE onmaru.catalog_dataset_revisions
                    SET status = 'PUBLISHED', published_at = CURRENT_TIMESTAMP
                    WHERE id = '%s'
                    """.formatted(stagedRevision));
            connection.commit();

            assertThat(singleUuid(statement, """
                    SELECT revision_id FROM onmaru.catalog_active_datasets WHERE dataset = 'odii-audio'
                    """)).isEqualTo(stagedRevision);
            assertThat(singleUuid(statement, """
                    SELECT revision_id FROM onmaru.operations_sync_watermarks WHERE dataset = 'odii-audio'
                    """)).isEqualTo(stagedRevision);
            assertThat(countRows(statement, """
                    SELECT COUNT(*)
                    FROM onmaru.audio_spot_versions spot
                    JOIN onmaru.audio_story_versions story
                      ON story.revision_id = spot.revision_id
                     AND story.spot_id = spot.spot_id
                    WHERE spot.revision_id = '%s'
                    """.formatted(stagedRevision))).isEqualTo(1);
        }
    }

    private static int activateAudioRevision(Statement statement, UUID baseRevision, UUID stagedRevision)
            throws Exception {
        return statement.executeUpdate("""
                UPDATE onmaru.catalog_active_datasets active
                SET revision_id = '%s', activated_at = CURRENT_TIMESTAMP
                WHERE active.dataset = 'odii-audio'
                  AND active.revision_id = '%s'
                  AND EXISTS (
                    SELECT 1
                    FROM onmaru.operations_sync_leases lease
                    WHERE lease.dataset = active.dataset
                      AND lease.owner_token = 'worker-a'
                      AND lease.generation = 1
                      AND lease.lease_until > CURRENT_TIMESTAMP
                  )
                """.formatted(stagedRevision, baseRevision));
    }

    private static void updateAudioWatermark(Statement statement, UUID revisionId) throws Exception {
        statement.execute("""
                UPDATE onmaru.operations_sync_watermarks
                SET source_modified_at = '2025-06-09T07:46:06Z',
                    external_id = '1204',
                    last_full_success_at = CURRENT_TIMESTAMP,
                    last_success_at = CURRENT_TIMESTAMP,
                    revision_id = '%s'
                WHERE dataset = 'odii-audio'
                """.formatted(revisionId));
    }

    private static UUID singleUuid(Statement statement, String sql) throws Exception {
        try (var resultSet = statement.executeQuery(sql)) {
            assertThat(resultSet.next()).isTrue();
            return resultSet.getObject(1, UUID.class);
        }
    }

    private static void insertRevision(Statement statement, UUID revisionId, String dataset) throws Exception {
        statement.execute("""
                INSERT INTO onmaru.catalog_dataset_revisions (
                    id, dataset, status, source_observed_at, fetched_at
                ) VALUES (
                    '%s', '%s', 'STAGING', '2026-09-15T00:00:00Z',
                    '2026-09-15T00:05:00Z'
                )
                """.formatted(revisionId, dataset));
    }

    private static void insertSpot(
            Statement statement,
            UUID spotId,
            String provider,
            String tid,
            String tlid,
            String langCode
    ) throws Exception {
        statement.execute("""
                INSERT INTO onmaru.audio_odii_spots (
                    id, provider, tid, tlid, lang_code, created_at
                ) VALUES (
                    '%s', '%s', '%s', '%s', '%s', CURRENT_TIMESTAMP
                )
                """.formatted(spotId, provider, tid, tlid, langCode));
    }

    private static void insertStory(
            Statement statement,
            UUID storyId,
            UUID spotId,
            String provider,
            String stid,
            String stlid,
            String langCode
    ) throws Exception {
        statement.execute("""
                INSERT INTO onmaru.audio_odii_stories (
                    id, spot_id, provider, stid, stlid, lang_code, created_at
                ) VALUES (
                    '%s', '%s', '%s', '%s', '%s', '%s', CURRENT_TIMESTAMP
                )
                """.formatted(storyId, spotId, provider, stid, stlid, langCode));
    }

    private static void insertSpotVersion(
            Statement statement,
            UUID revisionId,
            UUID spotId,
            String title,
            String status
    ) throws Exception {
        statement.execute("""
                INSERT INTO onmaru.audio_spot_versions (
                    revision_id, spot_id, title, address, location, status, hash
                ) VALUES (
                    '%s', '%s', '%s', '전북 전주시',
                    ST_SetSRID(ST_MakePoint(127.153, 35.815), 4326)::geography,
                    '%s', 'spot-hash'
                )
                """.formatted(revisionId, spotId, title, status));
    }

    private static void insertStoryVersion(
            Statement statement,
            UUID revisionId,
            UUID storyId,
            UUID spotId,
            String title,
            String status,
            Integer durationSeconds
    ) throws Exception {
        var durationValue = durationSeconds == null ? "NULL" : durationSeconds.toString();
        statement.execute("""
                INSERT INTO onmaru.audio_story_versions (
                    revision_id, story_id, spot_id, title, script, audio_url,
                    image_url, duration_seconds, status, hash
                ) VALUES (
                    '%s', '%s', '%s', '%s', 'script', 'https://example.com/audio.mp3',
                    'https://example.com/image.jpg', %s, '%s', 'story-hash'
                )
                """.formatted(revisionId, storyId, spotId, title, durationValue, status));
    }

    private static void insertSubtitleLine(
            Statement statement,
            UUID revisionId,
            UUID storyId,
            int position,
            String text,
            int startSeconds
    ) throws Exception {
        statement.execute("""
                INSERT INTO onmaru.audio_subtitle_lines (
                    revision_id, story_id, position, text, start_seconds, timing_mode
                ) VALUES (
                    '%s', '%s', %d, '%s', %d, 'OFFICIAL'
                )
                """.formatted(revisionId, storyId, position, text, startSeconds));
    }

    private static void insertPlaceLink(
            Statement statement,
            UUID placeId,
            UUID spotId,
            String reviewStatus
    ) throws Exception {
        statement.execute("""
                INSERT INTO onmaru.audio_place_odii_links (
                    place_id, spot_id, match_method, confidence, review_status, verified_at
                ) VALUES (
                    '%s', '%s', 'MANUAL_REFERENCE', NULL, '%s', CURRENT_TIMESTAMP
                )
                """.formatted(placeId, spotId, reviewStatus));
    }

    private static void resetAndMigrate() throws Exception {
        try (var connection = DriverManager.getConnection(jdbcUrl(), USERNAME, PASSWORD)) {
            PostgresTestDatabase.reset(connection);
        }
        Flyway.configure()
                .dataSource(jdbcUrl(), USERNAME, PASSWORD)
                .locations("classpath:db/migration/baseline")
                .baselineOnMigrate(true)
                .baselineVersion("0")
                .load()
                .migrate();
    }

    private static int countRows(Statement statement, String sql) throws Exception {
        try (var resultSet = statement.executeQuery(sql)) {
            assertThat(resultSet.next()).isTrue();
            return resultSet.getInt(1);
        }
    }

    private static String jdbcUrl() {
        return "jdbc:postgresql://%s:%d/%s".formatted(
                postgres.getHost(),
                postgres.getMappedPort(POSTGRES_PORT),
                DATABASE);
    }
}
