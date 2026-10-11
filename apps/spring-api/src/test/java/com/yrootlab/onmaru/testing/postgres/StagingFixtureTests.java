package com.yrootlab.onmaru.testing.postgres;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yrootlab.onmaru.audio.query.InMemoryOdiiStoryPopularityCounter;
import com.yrootlab.onmaru.catalog.application.query.mapinfo.MapInfoQueryService;
import com.yrootlab.onmaru.catalog.application.query.mapinfo.MapInfoViewportQueryService;
import com.yrootlab.onmaru.community.query.VisitReviewQueryService;
import com.yrootlab.onmaru.config.secrets.FakeSecretProvider;
import com.yrootlab.onmaru.identity.lifecycle.MemberLifecycleService;
import com.yrootlab.onmaru.identity.oauth.InMemoryIdentityStore;
import com.yrootlab.onmaru.identity.oauth.TokenHasher;
import com.yrootlab.onmaru.persistence.catalog.JdbcCatalogPublicPlaceIdStore;
import com.yrootlab.onmaru.persistence.catalog.JdbcMapInfoQueryRepository;
import com.yrootlab.onmaru.persistence.catalog.JdbcMapViewportQueryRepository;
import com.yrootlab.onmaru.persistence.community.JdbcVisitReviewStore;
import com.yrootlab.onmaru.tourism.audio.JdbcAudioRevisionStore;
import com.yrootlab.onmaru.web.audio.OdiiStoryConfiguration;
import com.yrootlab.onmaru.web.audio.OdiiStoryController;
import com.yrootlab.onmaru.web.map.MapInfoRequestExecutor;
import com.yrootlab.onmaru.web.map.place.MapInfoController;
import com.yrootlab.onmaru.web.map.viewport.MapInfoViewportController;
import com.yrootlab.onmaru.web.review.query.VisitReviewQueryController;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.core.env.MapPropertySource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.web.MockServletContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class StagingFixtureTests {

    private static final GenericContainer<?> POSTGRES = new GenericContainer<>(
            DockerImageName.parse("postgis/postgis:17-3.5-alpine"))
            .withExposedPorts(5432)
            .withEnv("POSTGRES_DB", "onmaru_test")
            .withEnv("POSTGRES_USER", "onmaru_test")
            .withEnv("POSTGRES_PASSWORD", "onmaru_test")
            .waitingFor(Wait.forLogMessage(".*database system is ready to accept connections.*\\n", 2));

    private static final Path SEED = Path.of("../../infra/lightsail/staging/seed.sql");
    private static final Path PILOT_SQL = Path.of("../../testing/kcontents-pilot");
    private static final String HIDDEN_REVIEW_ID = "54500000-0000-4000-8000-000000000113";
    private static final String USER_REVIEW_ID = "12340000-0000-4000-8000-000000000101";
    private AnnotationConfigWebApplicationContext publicApiContext;
    private MockMvc publicApi;

    @AfterEach
    void closePublicApiContext() {
        if (publicApiContext != null) publicApiContext.close();
    }

    @BeforeAll
    static void startPostgres() {
        POSTGRES.start();
    }

    @AfterAll
    static void stopPostgres() {
        POSTGRES.stop();
    }

    @BeforeEach
    void migrateEmptyDatabase() throws Exception {
        try (var connection = DriverManager.getConnection(jdbcUrl(), "onmaru_test", "onmaru_test")) {
            PostgresTestDatabase.reset(connection);
        }
        Flyway.configure()
                .dataSource(jdbcUrl(), "onmaru_test", "onmaru_test")
                .locations("classpath:db/migration/baseline")
                .baselineOnMigrate(true)
                .baselineVersion("0")
                .load()
                .migrate();
    }

    @Test
    void seedsConnectedPublicApiFixturesIdempotently() throws Exception {
        executeSeedForTestDatabase();
        try (var connection = DriverManager.getConnection(jdbcUrl(), "onmaru_test", "onmaru_test");
             var statement = connection.createStatement()) {
            statement.executeUpdate("""
                    UPDATE onmaru.catalog_place_versions
                    SET overview = 'drifted staging value'
                    WHERE place_id = '54500000-0000-4000-8000-000000000021'
                    """);
            statement.executeUpdate("""
                    UPDATE onmaru.map_place_read_projection
                    SET summary = 'drifted staging value'
                    WHERE place_id = '54500000-0000-4000-8000-000000000022'
                    """);
        }
        executeSeedForTestDatabase();

        try (var connection = DriverManager.getConnection(jdbcUrl(), "onmaru_test", "onmaru_test")) {
            assertThat(longValue(connection, """
                    SELECT count(*) FROM onmaru.map_place_read_projection
                    WHERE revision_id = '54500000-0000-4000-8000-000000000010'
                    """)).isEqualTo(100);
            assertThat(longValue(connection, """
                    SELECT count(*) FROM onmaru.community_visit_reviews
                    WHERE status = 'PUBLISHED' AND (
                      id::text LIKE '54500675-%'
                      OR id IN (
                        '54500000-0000-4000-8000-000000000111',
                        '54500000-0000-4000-8000-000000000112'
                      )
                    )
                    """)).isEqualTo(65);
            assertThat(longValue(connection, """
                    SELECT count(*) FROM onmaru.audio_story_versions
                    WHERE revision_id = '54500000-0000-4000-8000-000000000011'
                      AND status = 'ACTIVE'
                    """)).isEqualTo(65);
            assertThat(value(connection, """
                    SELECT overview FROM onmaru.catalog_place_versions
                    WHERE place_id = '54500000-0000-4000-8000-000000000021'
                    """)).isEqualTo("실제 관광지가 아닌 합성 테스트 데이터입니다.");
            assertThat(value(connection, """
                    SELECT summary FROM onmaru.map_place_read_projection
                    WHERE place_id = '54500000-0000-4000-8000-000000000022'
                    """)).isEqualTo("선택 필드가 적은 합성 테스트 데이터입니다.");
            assertThat(longValue(connection, """
                    SELECT count(DISTINCT sido_code)
                    FROM onmaru.map_place_read_projection
                    WHERE revision_id = '54500000-0000-4000-8000-000000000010'
                    """)).isGreaterThanOrEqualTo(4);
            assertThat(value(connection, """
                    SELECT (published_at AT TIME ZONE 'UTC')::text FROM onmaru.map_projection_publications
                    WHERE revision_id = '54500000-0000-4000-8000-000000000010'
                    """)).startsWith("2026-10-06 00:00:00");
            assertThat(value(connection, """
                    SELECT string_agg(canonical_category || ':' || count_value, ',' ORDER BY canonical_category)
                    FROM (
                      SELECT canonical_category, count(*)::text AS count_value
                      FROM onmaru.map_place_category_projection
                      WHERE revision_id = '54500000-0000-4000-8000-000000000010'
                      GROUP BY canonical_category
                    ) counts
                    """)).contains("CAFE:", "MARKET:", "SPOT:");
            assertThat(longValue(connection, """
                    SELECT row_count FROM onmaru.map_projection_publications
                    WHERE revision_id = '54500000-0000-4000-8000-000000000010'
                      AND projection_name = 'map_place_read_projection'
                    """)).isEqualTo(100);
            assertThat(longValue(connection, """
                    SELECT count(*) FROM onmaru.map_scope_count_projection
                    WHERE revision_id = '54500000-0000-4000-8000-000000000010'
                    """)).isPositive();

            assertThat(longValue(connection, """
                    SELECT count(*) FROM onmaru.community_visit_reviews
                    WHERE status = 'PUBLISHED'
                      AND id = '54500000-0000-4000-8000-000000000113'
                    """)).isZero();
            try (var statement = connection.createStatement(); var rows = statement.executeQuery("""
                    SELECT count(*)
                    FROM onmaru.catalog_active_datasets active
                    JOIN onmaru.catalog_place_versions place ON place.revision_id = active.revision_id
                    JOIN onmaru.catalog_place_public_ids public_id ON public_id.place_id = place.place_id
                    JOIN onmaru.catalog_place_image_versions image
                      ON image.revision_id = place.revision_id AND image.place_id = place.place_id
                    JOIN onmaru.catalog_place_content_tag_versions tag
                      ON tag.revision_id = place.revision_id AND tag.place_id = place.place_id
                    JOIN onmaru.community_visit_reviews review ON review.place_id = place.place_id
                    JOIN onmaru.audio_place_odii_links audio_link ON audio_link.place_id = place.place_id
                    JOIN onmaru.audio_story_versions story ON story.spot_id = audio_link.spot_id
                    WHERE active.dataset = 'kto-korean-tour'
                      AND public_id.public_id = 'p-staging-hanok-a'
                      AND place.status = 'ACTIVE'
                      AND review.status = 'PUBLISHED'
                      AND story.status = 'ACTIVE'
                    """)) {
                rows.next();
                assertThat(rows.getInt(1)).isGreaterThan(0);
            }
        }
    }

    @Test
    void publicControllersPageTheSeededReviewsAndStoriesWithReturnedCursors() throws Exception {
        executeSeedForTestDatabase();
        executeSeedForTestDatabase();

        var reviews = readPublicPages("/api/v1/visit-reviews", Map.of("scope", "ALL"), "id", 65);
        assertThat(reviews.sizes()).containsExactly(30, 30, 5);
        assertThat(reviews.hasMore()).containsExactly(true, true, false);
        assertThat(reviews.ids()).containsExactlyInAnyOrderElementsOf(fixtureReviewIds())
                .doesNotContain(HIDDEN_REVIEW_ID);

        var placeReviews = readPublicPages("/api/v1/places/p-staging-hanok-a/visit-reviews", Map.of(), "id", 31);
        assertThat(placeReviews.sizes()).containsExactly(30, 1);
        assertThat(placeReviews.hasMore()).containsExactly(true, false);
        assertThat(placeReviews.ids()).doesNotContain(HIDDEN_REVIEW_ID);
        var hiddenPlace = publicGet("/api/v1/places/p-staging-palace-c/visit-reviews", Map.of("limit", "30"));
        assertThat(hiddenPlace.path("items")).isEmpty();
        assertThat(hiddenPlace.path("totalCount").asLong()).isZero();

        var stories = readPublicPages("/api/v1/odii/stories", Map.of("language", "ko-KR"), "storyId", 65);
        assertThat(stories.sizes()).containsExactly(30, 30, 5);
        assertThat(stories.hasMore()).containsExactly(true, true, false);
        assertThat(stories.ids()).containsExactlyInAnyOrderElementsOf(fixtureStoryIds());
    }

    @Test
    void publicOdiiCursorOrdersEqualTimestampsByAscendingPublicIdentity() throws Exception {
        executeSeedForTestDatabase();
        List<String> tiedPublicIds;
        try (var connection = DriverManager.getConnection(jdbcUrl(), "onmaru_test", "onmaru_test");
             var statement = connection.createStatement()) {
            statement.executeUpdate("""
                    UPDATE onmaru.audio_story_versions SET source_modified_at = '2026-10-06T12:00:00Z'
                    WHERE story_id::text LIKE '54500675-%'
                    """);
            tiedPublicIds = readIds(connection, """
                    SELECT 'odii-story-' || public_id::text FROM onmaru.audio_odii_stories
                    WHERE id::text LIKE '54500675-%'
                    """).stream().sorted().toList();
        }

        var stories = readPublicPages("/api/v1/odii/stories", Map.of("language", "ko-KR"), "storyId", 65);
        assertThat(stories.sizes()).containsExactly(30, 30, 5);
        assertThat(stories.ids().subList(0, 62)).containsExactlyElementsOf(tiedPublicIds);
        assertThat(stories.ids()).containsExactlyInAnyOrderElementsOf(fixtureStoryIds());
    }

    @Test
    void publicMapControllersReturnAllFixtureIdsAndFourFilteredRenderModes() throws Exception {
        executeSeedForTestDatabase();
        var allPlaceIds = new ArrayList<String>();
        var categories = List.of("SPOT", "CAFE", "MARKET");
        var counts = List.of(34, 33, 33);
        for (int i = 0; i < categories.size(); i++) {
            String category = categories.get(i);
            var places = readPublicPages("/api/v1/map/info/places", Map.of("category", category), "placeId", counts.get(i));
            assertThat(places.sizes()).containsExactly(30, counts.get(i) - 30);
            assertThat(places.ids()).containsExactlyInAnyOrderElementsOf(fixtureMapIds(category));
            allPlaceIds.addAll(places.ids());
            for (int zoom : List.of(1, 6, 9, 11)) {
                var viewport = publicGet("/api/v1/map/info/viewport", Map.of(
                        "bbox", "124,33,132,39", "zoomLevel", Integer.toString(zoom), "category", category));
                String mode = switch (zoom) {
                    case 1 -> "PLACE";
                    case 6 -> "CLUSTER";
                    case 9 -> "DISTRICT";
                    default -> "REGION";
                };
                assertThat(viewport.path("renderMode").asText()).isEqualTo(mode);
                assertThat(viewport.path("totalCountInViewport").asLong()).isEqualTo(counts.get(i).longValue());
                assertThat(viewport.path("coverage").asText()).isEqualTo("COMPLETE");
                long represented = 0;
                for (var item : viewport.path("items")) represented += item.path("count").asLong();
                assertThat(represented).isEqualTo(counts.get(i).longValue());
            }
        }
        assertThat(allPlaceIds).hasSize(100).doesNotHaveDuplicates();
        try (var connection = DriverManager.getConnection(jdbcUrl(), "onmaru_test", "onmaru_test")) {
            assertThat(allPlaceIds).containsExactlyInAnyOrderElementsOf(readIds(connection, """
                    SELECT public_id FROM onmaru.map_place_read_projection
                    WHERE revision_id = '54500000-0000-4000-8000-000000000010'
                    """));
        }
    }

    @Test
    void reseedsGeneratedReviewsWhilePreservingLikesReportsAndModerationHistory() throws Exception {
        executeSeedForTestDatabase();
        try (var connection = DriverManager.getConnection(jdbcUrl(), "onmaru_test", "onmaru_test");
             var statement = connection.createStatement()) {
            statement.executeUpdate("""
                    INSERT INTO onmaru.community_review_likes (review_id, member_id, created_at)
                    VALUES ('54500675-0000-4000-8600-000000000001',
                            '54500000-0000-4000-8000-000000000101', '2026-10-07T01:00:00Z')
                    """);
            statement.executeUpdate("""
                    INSERT INTO onmaru.community_review_reports
                      (id, review_id, reporter_member_id, reason, detail, status, created_at)
                    VALUES ('12340000-0000-4000-8000-000000000201',
                            '54500675-0000-4000-8600-000000000001',
                            '54500000-0000-4000-8000-000000000101',
                            'OTHER', '사용자가 남긴 신고 내용', 'OPEN', '2026-10-07T02:00:00Z')
                    """);
            statement.executeUpdate("""
                    INSERT INTO onmaru.community_review_moderation_actions
                      (id, review_id, actor_type, actor_ref, previous_status, next_status, reason, created_at)
                    VALUES ('12340000-0000-4000-8000-000000000202',
                            '54500675-0000-4000-8600-000000000001',
                            'OPERATOR', 'staging-test-operator', 'PUBLISHED', 'HIDDEN',
                            '사용자가 남긴 검수 이력', '2026-10-07T03:00:00Z')
                    """);
            statement.executeUpdate("""
                    UPDATE onmaru.community_visit_reviews
                    SET status = 'HIDDEN', text = 'drifted generated review'
                    WHERE id = '54500675-0000-4000-8600-000000000001'
                    """);
        }

        executeSeedForTestDatabase();

        try (var connection = DriverManager.getConnection(jdbcUrl(), "onmaru_test", "onmaru_test")) {
            assertThat(longValue(connection, """
                    SELECT count(*) FROM onmaru.community_review_likes
                    WHERE review_id = '54500675-0000-4000-8600-000000000001'
                      AND member_id = '54500000-0000-4000-8000-000000000101'
                      AND created_at = '2026-10-07T01:00:00Z'
                    """)).isEqualTo(1);
            assertThat(longValue(connection, """
                    SELECT count(*) FROM onmaru.community_review_reports
                    WHERE id = '12340000-0000-4000-8000-000000000201'
                      AND review_id = '54500675-0000-4000-8600-000000000001'
                      AND reporter_member_id = '54500000-0000-4000-8000-000000000101'
                      AND reason = 'OTHER' AND detail = '사용자가 남긴 신고 내용'
                      AND status = 'OPEN' AND created_at = '2026-10-07T02:00:00Z'
                      AND resolved_at IS NULL
                    """)).isEqualTo(1);
            assertThat(longValue(connection, """
                    SELECT count(*) FROM onmaru.community_review_moderation_actions
                    WHERE id = '12340000-0000-4000-8000-000000000202'
                      AND review_id = '54500675-0000-4000-8600-000000000001'
                      AND actor_type = 'OPERATOR' AND actor_ref = 'staging-test-operator'
                      AND previous_status = 'PUBLISHED' AND next_status = 'HIDDEN'
                      AND reason = '사용자가 남긴 검수 이력' AND created_at = '2026-10-07T03:00:00Z'
                    """)).isEqualTo(1);
            assertThat(value(connection, """
                    SELECT text FROM onmaru.community_visit_reviews
                    WHERE id = '54500675-0000-4000-8600-000000000001'
                    """)).isEqualTo("페이지네이션을 확인하는 합성 방문 후기 001입니다.");
            assertThat(longValue(connection, """
                    SELECT count(*) FROM onmaru.community_visit_reviews
                    WHERE id::text LIKE '54500675-%' AND status = 'PUBLISHED'
                    """)).isEqualTo(63);
        }
    }

    @Test
    void retiresOutOfRangeFixtureReviewsWithoutDeletingTheirUserReferences() throws Exception {
        executeSeedForTestDatabase();
        try (var connection = DriverManager.getConnection(jdbcUrl(), "onmaru_test", "onmaru_test");
             var statement = connection.createStatement()) {
            statement.executeUpdate("""
                    INSERT INTO onmaru.community_visit_reviews
                      (id, member_id, place_id, text, status, created_at, mood, score, tags,
                       public_place_id, place_name, region_code, latitude, longitude)
                    SELECT '54500675-0000-4000-8600-000000000064', member_id, place_id,
                           '이전 fixture 범위의 후기', 'PUBLISHED', '2026-10-07T00:00:00Z', mood, score, tags,
                           public_place_id, place_name, region_code, latitude, longitude
                    FROM onmaru.community_visit_reviews
                    WHERE id = '54500675-0000-4000-8600-000000000001'
                    """);
            statement.executeUpdate("""
                    INSERT INTO onmaru.community_review_likes (review_id, member_id, created_at)
                    VALUES ('54500675-0000-4000-8600-000000000064',
                            '54500000-0000-4000-8000-000000000101', '2026-10-07T01:00:00Z')
                    """);
            statement.executeUpdate("""
                    INSERT INTO onmaru.community_review_reports
                      (id, review_id, reporter_member_id, reason, detail, status, created_at)
                    VALUES ('12340000-0000-4000-8000-000000000301',
                            '54500675-0000-4000-8600-000000000064',
                            '54500000-0000-4000-8000-000000000101',
                            'OTHER', '잔존 fixture에 작성한 사용자 신고', 'OPEN', '2026-10-07T02:00:00Z')
                    """);
            statement.executeUpdate("""
                    INSERT INTO onmaru.community_review_moderation_actions
                      (id, review_id, actor_type, actor_ref, previous_status, next_status, reason, created_at)
                    VALUES ('12340000-0000-4000-8000-000000000302',
                            '54500675-0000-4000-8600-000000000064',
                            'OPERATOR', 'staging-test-operator', 'PUBLISHED', 'HIDDEN',
                            '잔존 fixture에 남긴 검수 이력', '2026-10-07T03:00:00Z')
                    """);
        }

        executeSeedForTestDatabase();
        executeSeedForTestDatabase();

        var reviews = readPublicPages("/api/v1/visit-reviews", Map.of("scope", "ALL"), "id", 65);
        assertThat(reviews.sizes()).containsExactly(30, 30, 5);
        assertThat(reviews.ids()).containsExactlyInAnyOrderElementsOf(fixtureReviewIds())
                .doesNotContain("54500675-0000-4000-8600-000000000064");
        try (var connection = DriverManager.getConnection(jdbcUrl(), "onmaru_test", "onmaru_test")) {
            assertThat(longValue(connection, """
                    SELECT count(*) FROM onmaru.community_visit_reviews
                    WHERE id = '54500675-0000-4000-8600-000000000064'
                      AND status = 'HIDDEN' AND text = '이전 fixture 범위의 후기'
                    """)).isEqualTo(1);
            assertThat(longValue(connection, """
                    SELECT count(*) FROM onmaru.community_review_likes
                    WHERE review_id = '54500675-0000-4000-8600-000000000064'
                      AND member_id = '54500000-0000-4000-8000-000000000101'
                      AND created_at = '2026-10-07T01:00:00Z'
                    """)).isEqualTo(1);
            assertThat(longValue(connection, """
                    SELECT count(*) FROM onmaru.community_review_reports
                    WHERE id = '12340000-0000-4000-8000-000000000301'
                      AND review_id = '54500675-0000-4000-8600-000000000064'
                      AND reporter_member_id = '54500000-0000-4000-8000-000000000101'
                      AND reason = 'OTHER' AND detail = '잔존 fixture에 작성한 사용자 신고'
                      AND status = 'OPEN' AND created_at = '2026-10-07T02:00:00Z'
                      AND resolved_at IS NULL
                    """)).isEqualTo(1);
            assertThat(longValue(connection, """
                    SELECT count(*) FROM onmaru.community_review_moderation_actions
                    WHERE id = '12340000-0000-4000-8000-000000000302'
                      AND review_id = '54500675-0000-4000-8600-000000000064'
                      AND actor_type = 'OPERATOR' AND actor_ref = 'staging-test-operator'
                      AND previous_status = 'PUBLISHED' AND next_status = 'HIDDEN'
                      AND reason = '잔존 fixture에 남긴 검수 이력' AND created_at = '2026-10-07T03:00:00Z'
                    """)).isEqualTo(1);
        }
    }

    @Test
    void reseedsGeneratedPlacesWhilePreservingUserReviews() throws Exception {
        executeSeedForTestDatabase();
        try (var connection = DriverManager.getConnection(jdbcUrl(), "onmaru_test", "onmaru_test");
             var statement = connection.createStatement()) {
            statement.executeUpdate("""
                    INSERT INTO onmaru.community_visit_reviews
                      (id, member_id, place_id, text, status, created_at, mood, score, tags,
                       public_place_id, place_name, region_code, latitude, longitude)
                    VALUES ('12340000-0000-4000-8000-000000000101',
                            '54500000-0000-4000-8000-000000000101',
                            '54500675-0000-4000-8300-000000000001',
                            '일반 사용자가 생성 장소에 작성한 후기', 'PUBLISHED', '2026-10-07T04:00:00Z',
                            '북적', 4, '["사용자후기"]', 'p-staging-generated-001',
                            '합성 서울지구 장소 001', 'STG-SEOUL-01', 37.5665, 126.9780)
                    """);
            statement.executeUpdate("""
                    INSERT INTO onmaru.community_visit_reviews
                      (id, member_id, place_id, text, status, created_at, mood, score, tags,
                       public_place_id, place_name, region_code, latitude, longitude)
                    SELECT ('12340000-0000-4000-8000-' || lpad(n::text, 12, '0'))::uuid,
                           member_id, place_id, text, status, created_at, mood, score, tags,
                           public_place_id, place_name, region_code, latitude, longitude
                    FROM onmaru.community_visit_reviews CROSS JOIN generate_series(102, 126) AS series(n)
                    WHERE id = '12340000-0000-4000-8000-000000000101'
                    """);
            statement.executeUpdate("""
                    UPDATE onmaru.catalog_place_identity SET created_at = '2026-10-01T00:00:00Z'
                    WHERE id = '54500675-0000-4000-8300-000000000001'
                    """);
        }

        executeSeedForTestDatabase();

        var reviews = readPublicPages("/api/v1/visit-reviews", Map.of("scope", "ALL"), "id", 91);
        assertThat(reviews.sizes()).containsExactly(30, 30, 30, 1);
        assertThat(reviews.hasMore()).containsExactly(true, true, true, false);
        assertThat(reviews.ids()).contains(USER_REVIEW_ID).containsAll(fixtureReviewIds());
        assertThat(reviews.ids().stream().filter(id -> id.startsWith("12340000-")).toList()).hasSize(26);
        assertThat(reviews.ids().stream().filter(fixtureReviewIds()::contains).toList()).hasSize(65);

        try (var connection = DriverManager.getConnection(jdbcUrl(), "onmaru_test", "onmaru_test")) {
            assertThat(longValue(connection, """
                    SELECT count(*) FROM onmaru.community_visit_reviews
                    WHERE id = '12340000-0000-4000-8000-000000000101'
                      AND member_id = '54500000-0000-4000-8000-000000000101'
                      AND place_id = '54500675-0000-4000-8300-000000000001'
                      AND text = '일반 사용자가 생성 장소에 작성한 후기'
                      AND status = 'PUBLISHED' AND created_at = '2026-10-07T04:00:00Z'
                      AND mood = '북적' AND score = 4 AND tags = '["사용자후기"]'::jsonb
                      AND public_place_id = 'p-staging-generated-001'
                      AND place_name = '합성 서울지구 장소 001' AND region_code = 'STG-SEOUL-01'
                      AND latitude = 37.5665 AND longitude = 126.9780 AND deleted_at IS NULL
                    """)).isEqualTo(1);
            assertThat(longValue(connection, """
                    SELECT count(*) FROM onmaru.catalog_place_identity
                    WHERE id = '54500675-0000-4000-8300-000000000001'
                      AND created_at = '2026-10-06T00:00:00Z'
                    """)).isEqualTo(1);
            assertThat(longValue(connection, """
                    SELECT count(*) FROM onmaru.map_place_read_projection
                    WHERE revision_id = '54500000-0000-4000-8000-000000000010'
                    """)).isEqualTo(100);
        }
    }

    @Test
    void refusesToSeedAnyDatabaseExceptStaging() throws Exception {
        var sql = seedSql();
        try (var connection = DriverManager.getConnection(jdbcUrl(), "onmaru_test", "onmaru_test");
             var statement = connection.createStatement()) {
            assertThatThrownBy(() -> statement.execute(sql))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("Refusing staging seed");
        }
    }

    private PublicPages readPublicPages(String path, Map<String, String> params, String idField, int total) throws Exception {
        var pagedIds = new ArrayList<String>();
        var pageSizes = new ArrayList<Integer>();
        var hasMoreByPage = new ArrayList<Boolean>();
        String cursor = null;
        do {
            var requestParams = new java.util.HashMap<>(params);
            requestParams.put("limit", "30");
            if (cursor != null) requestParams.put("cursor", cursor);
            var page = publicGet(path, requestParams);
            assertThat(page.path("totalCount").asLong()).as(path).isEqualTo(total);
            pageSizes.add(page.path("items").size());
            for (var item : page.path("items")) {
                assertThat(item.path(idField).asText()).isNotBlank();
                pagedIds.add(item.path(idField).asText());
            }
            boolean hasMore = !page.path("nextCursor").isNull();
            if (page.has("hasMore")) assertThat(page.path("hasMore").asBoolean()).isEqualTo(hasMore);
            hasMoreByPage.add(hasMore);
            cursor = hasMore ? page.path("nextCursor").asText() : null;
            if (hasMore) assertThat(cursor).isNotBlank();
            else {
                assertThat(page.has("nextCursor")).isTrue();
                assertThat(page.get("nextCursor").isNull()).isTrue();
            }
            assertThat(pageSizes.size()).isLessThan(10);
        } while (cursor != null);

        assertThat(pagedIds).hasSize(total).doesNotHaveDuplicates();
        return new PublicPages(pagedIds, pageSizes, hasMoreByPage);
    }

    private JsonNode publicGet(String path, Map<String, String> params) throws Exception {
        if (publicApi == null) initializePublicApi();
        var request = get(path);
        params.forEach(request::param);
        var response = publicApi.perform(request).andExpect(status().isOk()).andReturn().getResponse();
        return new ObjectMapper().readTree(response.getContentAsString());
    }

    private void initializePublicApi() {
        var dataSource = new DriverManagerDataSource(jdbcUrl(), "onmaru_test", "onmaru_test");
        var clock = Clock.fixed(Instant.parse("2026-10-07T05:00:00Z"), ZoneOffset.UTC);
        publicApiContext = new AnnotationConfigWebApplicationContext();
        publicApiContext.setServletContext(new MockServletContext());
        publicApiContext.getEnvironment().setActiveProfiles("production");
        // This is the public audio host configured by staging/compose.yaml.
        publicApiContext.getEnvironment().getPropertySources().addFirst(new MapPropertySource(
                "staging-fixture", Map.of("onmaru.audio.public-hosts", "samplelib.com")));
        publicApiContext.addBeanFactoryPostProcessor(beans -> {
            beans.registerSingleton("dataSource", dataSource);
            beans.registerSingleton("clock", clock);
            beans.registerSingleton("secretProvider", new FakeSecretProvider());
            beans.registerSingleton("memberLifecycleService", new MemberLifecycleService(
                    new InMemoryIdentityStore(), new TokenHasher("staging-fixture-test-secret"), clock));
            beans.registerSingleton("visitReviewQueryService", new VisitReviewQueryService(
                    new JdbcVisitReviewStore(dataSource, new JdbcCatalogPublicPlaceIdStore(dataSource)), clock));
            beans.registerSingleton("mapInfoQueryService", new MapInfoQueryService(new JdbcMapInfoQueryRepository(dataSource)));
            beans.registerSingleton("mapInfoViewportQueryService", new MapInfoViewportQueryService(new JdbcMapViewportQueryRepository(dataSource)));
            beans.registerSingleton("mapInfoRequestExecutor", new MapInfoRequestExecutor(Duration.ofSeconds(2)));
            beans.registerSingleton("audioRevisionStore", new JdbcAudioRevisionStore(dataSource));
            beans.registerSingleton("odiiStoryPopularityPort", new InMemoryOdiiStoryPopularityCounter());
        });
        publicApiContext.register(PublicMvcConfiguration.class, OdiiStoryConfiguration.class,
                VisitReviewQueryController.class, OdiiStoryController.class,
                MapInfoController.class, MapInfoViewportController.class);
        publicApiContext.refresh();
        publicApi = MockMvcBuilders.webAppContextSetup(publicApiContext).build();
    }

    private List<String> fixtureReviewIds() {
        var ids = new ArrayList<String>();
        ids.add("54500000-0000-4000-8000-000000000111");
        ids.add("54500000-0000-4000-8000-000000000112");
        for (int n = 1; n <= 63; n++) ids.add("54500675-0000-4000-8600-%012d".formatted(n));
        return ids;
    }

    private List<String> fixtureMapIds(String category) {
        var ids = new ArrayList<String>();
        int firstGeneratedIndex = switch (category) {
            case "SPOT" -> {
                ids.add("p-staging-hanok-a");
                ids.add("p-staging-palace-c");
                yield 1;
            }
            case "CAFE" -> {
                ids.add("p-staging-hanok-b");
                yield 2;
            }
            case "MARKET" -> {
                ids.add("p-staging-market-d");
                yield 3;
            }
            default -> throw new IllegalArgumentException(category);
        };
        for (int n = firstGeneratedIndex; n <= 96; n += 3) ids.add("p-staging-generated-%03d".formatted(n));
        return ids;
    }

    private List<String> fixtureStoryIds() throws SQLException {
        try (var connection = DriverManager.getConnection(jdbcUrl(), "onmaru_test", "onmaru_test")) {
            // Identity selection only; eligibility, ordering and cursor processing run through the public API.
            return readIds(connection, """
                    SELECT 'odii-story-' || public_id::text FROM onmaru.audio_odii_stories
                    WHERE id::text LIKE '54500675-%' OR id IN (
                      '54500000-0000-4000-8000-000000000211',
                      '54500000-0000-4000-8000-000000000212',
                      '54500000-0000-4000-8000-000000000213')
                    """);
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    @EnableWebMvc
    static class PublicMvcConfiguration { }

    private record PublicPages(List<String> ids, List<Integer> sizes, List<Boolean> hasMore) { }

    private List<String> readIds(java.sql.Connection connection, String sql) throws SQLException {
        var ids = new ArrayList<String>();
        try (var statement = connection.createStatement(); var rows = statement.executeQuery(sql)) {
            while (rows.next()) ids.add(rows.getString(1));
        }
        return ids;
    }

    private void executeSeedForTestDatabase() throws Exception {
        var sql = seedSql().replace("current_database() <> 'onmaru_staging'", "current_database() <> 'onmaru_test'");
        try (var connection = DriverManager.getConnection(jdbcUrl(), "onmaru_test", "onmaru_test");
             var statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    @Test
    void syntheticDiscoveryRevisionsSupportRetainedVisibilityRollbackAndCleanup() throws Exception {
        executeSeedForTestDatabase();
        executePilotSql("staging-discovery-fixture.sql");
        executePilotSql("staging-discovery-fixture.sql"); // idempotent staging restart
        try (var connection = DriverManager.getConnection(jdbcUrl(), "onmaru_test", "onmaru_test")) {
            assertThat(longValue(connection, "SELECT count(*) FROM onmaru.selected_discovery_public_visible")).isEqualTo(3);
            assertThat(longValue(connection, """
                    SELECT count(*) FROM onmaru.selected_discovery_public_items p
                    JOIN onmaru.selected_discovery_candidates c USING (revision_id,content_id)
                    JOIN onmaru.selected_discovery_approvals a USING (content_id)
                    WHERE p.revision_id='69100000-0000-4000-8000-000000000001'
                      AND c.list_hash=a.list_hash AND c.detail_hash=a.detail_hash
                    """)).isEqualTo(2);
            assertThat(value(connection, "SELECT revision_id::text FROM onmaru.selected_discovery_active"))
                    .isEqualTo("69100000-0000-4000-8000-000000000002");
            assertThat(value(connection, "SELECT revision_id::text FROM onmaru.catalog_active_datasets WHERE dataset='kto-korean-tour'"))
                    .isEqualTo("54500000-0000-4000-8000-000000000010");
            try (var statement = connection.createStatement()) {
                statement.execute("DELETE FROM onmaru.selected_discovery_approvals WHERE content_id='staging-691-3'");
            }
            assertThat(longValue(connection, "SELECT count(*) FROM onmaru.selected_discovery_public_visible")).isEqualTo(2);
        }
        var rollback = Files.readString(PILOT_SQL.resolve("rollback-discovery.sql"))
                .replace(":'expected_active'", "'69100000-0000-4000-8000-000000000002'")
                .replace(":'target_revision'", "'69100000-0000-4000-8000-000000000001'");
        try (var connection = DriverManager.getConnection(jdbcUrl(), "onmaru_test", "onmaru_test");
             var statement = connection.createStatement()) {
            statement.execute(rollback);
        }
        try (var connection = DriverManager.getConnection(jdbcUrl(), "onmaru_test", "onmaru_test")) {
            assertThat(value(connection, "SELECT revision_id::text FROM onmaru.selected_discovery_active"))
                    .isEqualTo("69100000-0000-4000-8000-000000000001");
            assertThat(longValue(connection, "SELECT count(*) FROM onmaru.selected_discovery_public_visible")).isEqualTo(2);
        }
        executePilotSql("remove-staging-discovery-fixture.sql");
        try (var connection = DriverManager.getConnection(jdbcUrl(), "onmaru_test", "onmaru_test")) {
            assertThat(longValue(connection, "SELECT count(*) FROM onmaru.selected_discovery_revisions")).isZero();
            assertThat(longValue(connection, "SELECT count(*) FROM onmaru.selected_discovery_active")).isZero();
            assertThat(value(connection, "SELECT revision_id::text FROM onmaru.catalog_active_datasets WHERE dataset='kto-korean-tour'"))
                    .isEqualTo("54500000-0000-4000-8000-000000000010");
        }
    }

    private void executePilotSql(String file) throws Exception {
        var sql = Files.readString(PILOT_SQL.resolve(file))
                .replace("\\set ON_ERROR_STOP on", "")
                .replace("current_database() <> 'onmaru_staging'", "current_database() <> 'onmaru_test'");
        try (var connection = DriverManager.getConnection(jdbcUrl(), "onmaru_test", "onmaru_test");
             var statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private String seedSql() throws Exception {
        return Files.readString(SEED).replace("\\set ON_ERROR_STOP on", "");
    }

    private long longValue(java.sql.Connection connection, String sql) throws SQLException {
        try (var statement = connection.createStatement(); var rows = statement.executeQuery(sql)) {
            rows.next();
            return rows.getLong(1);
        }
    }

    private String value(java.sql.Connection connection, String sql) throws SQLException {
        try (var statement = connection.createStatement(); var rows = statement.executeQuery(sql)) {
            rows.next();
            return rows.getString(1);
        }
    }

    private static String jdbcUrl() {
        return "jdbc:postgresql://" + POSTGRES.getHost() + ":" + POSTGRES.getMappedPort(5432) + "/onmaru_test";
    }
}
