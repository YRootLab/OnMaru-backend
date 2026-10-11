package com.yrootlab.onmaru.testing.postgres;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yrootlab.onmaru.catalog.application.qualification.DiscoveryCandidatePolicy;
import com.yrootlab.onmaru.catalog.application.qualification.SourceRecord;
import com.yrootlab.onmaru.catalog.application.selectedsync.DiscoverySourceHash;
import com.yrootlab.onmaru.catalog.application.selectedsync.SelectedDiscoverySync;
import com.yrootlab.onmaru.persistence.catalog.selected.JdbcSelectedDiscoveryStore;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

import java.sql.DriverManager;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;

import static org.assertj.core.api.Assertions.assertThat;

class JdbcSelectedDiscoveryRevisionTests {
    private static final GenericContainer<?> POSTGRES = new GenericContainer<>(DockerImageName.parse("postgis/postgis:17-3.5-alpine"))
            .withExposedPorts(5432).withEnv("POSTGRES_DB", "onmaru_test")
            .withEnv("POSTGRES_USER", "onmaru_test").withEnv("POSTGRES_PASSWORD", "onmaru_test")
            .waitingFor(Wait.forLogMessage(".*database system is ready to accept connections.*\\n", 2));

    @BeforeAll static void start() { POSTGRES.start(); }
    @AfterAll static void stop() { POSTGRES.stop(); }

    @Test void separatePointerFirstPublishCasApprovalRevokeAndSharedIdentity() throws Exception {
        String url = "jdbc:postgresql://" + POSTGRES.getHost() + ":" + POSTGRES.getMappedPort(5432) + "/onmaru_test";
        try (var connection = DriverManager.getConnection(url, "onmaru_test", "onmaru_test")) {
            PostgresTestDatabase.reset(connection);
        }
        Flyway.configure().dataSource(url, "onmaru_test", "onmaru_test")
                .locations("classpath:db/migration/baseline").baselineOnMigrate(true).baselineVersion("0").load().migrate();
        var ds = new DriverManagerDataSource(url, "onmaru_test", "onmaru_test");
        var jdbc = new JdbcTemplate(ds);
        var store = new JdbcSelectedDiscoveryStore(ds, new ObjectMapper());

        UUID legacyRevision = UUID.randomUUID();
        jdbc.update("INSERT INTO onmaru.catalog_dataset_revisions(id,dataset,status,fetched_at,published_at) VALUES (?,'kto-korean-tour','PUBLISHED',now(),now())", legacyRevision);
        jdbc.update("INSERT INTO onmaru.catalog_active_datasets(dataset,revision_id,activated_at) VALUES ('kto-korean-tour',?,now())", legacyRevision);

        // Legacy source first: discovery must reuse its UUID.
        UUID legacyPlace = UUID.randomUUID();
        UUID legacySource = UUID.randomUUID();
        jdbc.update("INSERT INTO onmaru.catalog_place_identity(id,created_at) VALUES (?,now())", legacyPlace);
        jdbc.update("INSERT INTO onmaru.catalog_place_sources(id,place_id,provider,dataset,external_id,language,fetched_at) VALUES (?,?,'kto-tourapi-korean','kto-korean-tour','1','ko-KR',now())", legacySource, legacyPlace);
        jdbc.update("INSERT INTO onmaru.catalog_place_versions(revision_id,place_id,source_ref_id,name,category,visit_review_eligible,status,normalized_hash) VALUES (?,?,?,'기존 장소','HISTORIC_SITE',true,'ACTIVE',?)",
                legacyRevision, legacyPlace, legacySource, "a".repeat(64));
        jdbc.update("INSERT INTO onmaru.catalog_place_public_ids(public_id,place_id) VALUES ('p-tourapi-1',?)", legacyPlace);
        var legacyPublicIds = jdbc.queryForList("""
                SELECT public_id FROM onmaru.catalog_place_public_ids pid
                JOIN onmaru.catalog_place_versions version ON version.place_id=pid.place_id
                JOIN onmaru.catalog_active_datasets active ON active.revision_id=version.revision_id
                WHERE active.dataset='kto-korean-tour' AND version.status='ACTIVE'
                ORDER BY public_id
                """, String.class);
        var item1 = item("1");
        store.saveApproval("1", item1.approval());
        UUID run1 = UUID.randomUUID();
        UUID run2 = UUID.randomUUID();
        stage(store, run1, item1, Instant.parse("2026-10-12T18:00:00Z"));
        stage(store, run2, item1, Instant.parse("2026-10-19T18:00:00Z"));
        var start = new CountDownLatch(1);
        var first = CompletableFuture.supplyAsync(() -> publishAfter(start, store, run1, null, item1));
        var second = CompletableFuture.supplyAsync(() -> publishAfter(start, store, run2, null, item1));
        start.countDown();
        assertThat(List.of(first.get(), second.get())).containsExactlyInAnyOrder(true, false);
        UUID active = jdbc.queryForObject("SELECT revision_id FROM onmaru.selected_discovery_active", UUID.class);
        assertThat(active).isIn(run1, run2);
        assertThat(jdbc.queryForObject("SELECT place_id FROM onmaru.selected_discovery_public_items WHERE revision_id=?", UUID.class, active))
                .isEqualTo(legacyPlace);
        assertThat(jdbc.queryForObject("SELECT revision_id FROM onmaru.catalog_active_datasets WHERE dataset='kto-korean-tour'", UUID.class))
                .isEqualTo(legacyRevision);
        assertThat(jdbc.queryForList("""
                SELECT public_id FROM onmaru.catalog_place_public_ids pid
                JOIN onmaru.catalog_place_versions version ON version.place_id=pid.place_id
                JOIN onmaru.catalog_active_datasets active ON active.revision_id=version.revision_id
                WHERE active.dataset='kto-korean-tour' AND version.status='ACTIVE'
                ORDER BY public_id
                """, String.class)).isEqualTo(legacyPublicIds);

        // A stale in-memory approval cannot publish after an operator revokes it.
        var item2 = item("2");
        store.saveApproval("2", item2.approval());
        UUID revokedRun = UUID.randomUUID();
        stage(store, revokedRun, item2, Instant.parse("2026-10-26T18:00:00Z"));
        store.revokeApproval("2", "reviewer");
        assertThat(store.publish(revokedRun, active, Map.of("2", item2), report(revokedRun))).isFalse();
        assertThat(jdbc.queryForObject("SELECT revision_id FROM onmaru.selected_discovery_active", UUID.class)).isEqualTo(active);

        // Discovery first: the legacy source key is already reserved for the same place UUID.
        var item3 = item("3");
        store.saveApproval("3", item3.approval());
        UUID discoveryRun = UUID.randomUUID();
        stage(store, discoveryRun, item3, Instant.parse("2026-11-02T18:00:00Z"));
        assertThat(store.publish(discoveryRun, active, Map.of("3", item3), report(discoveryRun))).isTrue();
        UUID discoveryPlace = jdbc.queryForObject("SELECT place_id FROM onmaru.selected_discovery_public_items WHERE revision_id=?", UUID.class, discoveryRun);
        assertThat(jdbc.queryForObject("SELECT place_id FROM onmaru.catalog_place_sources WHERE provider='kto-tourapi-korean' AND dataset='kto-korean-tour' AND external_id='3' AND language='ko-KR'", UUID.class))
                .isEqualTo(discoveryPlace);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM onmaru.catalog_place_sources WHERE provider='kto-tourapi-korean' AND dataset='kto-korean-tour' AND external_id='3' AND language='ko-KR'", Integer.class))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT revision_id FROM onmaru.catalog_active_datasets WHERE dataset='kto-korean-tour'", UUID.class))
                .isEqualTo(legacyRevision);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM onmaru.selected_discovery_public_visible", Integer.class)).isEqualTo(1);
        store.revokeApproval("3", "rights-reviewer");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM onmaru.selected_discovery_public_visible", Integer.class)).isZero();
        assertThat(store.rollback(discoveryRun, active)).isTrue();
        assertThat(jdbc.queryForObject("SELECT revision_id FROM onmaru.selected_discovery_active", UUID.class)).isEqualTo(active);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM onmaru.selected_discovery_public_visible", Integer.class)).isEqualTo(1);

        // Concurrent legacy-source registration and discovery publication settle on one canonical UUID.
        var item4 = item("4");
        store.saveApproval("4", item4.approval());
        UUID raceRun = UUID.randomUUID();
        stage(store, raceRun, item4, Instant.parse("2026-11-09T18:00:00Z"));
        UUID legacyProposed = UUID.randomUUID();
        jdbc.update("INSERT INTO onmaru.catalog_place_identity(id,created_at) VALUES (?,now())", legacyProposed);
        var race = new CountDownLatch(1);
        var discovery = CompletableFuture.supplyAsync(() -> publishAfter(race, store, raceRun, active, item4));
        var legacy = CompletableFuture.runAsync(() -> {
            try { race.await(); } catch (InterruptedException exception) { Thread.currentThread().interrupt(); throw new IllegalStateException(exception); }
            jdbc.update("""
                    INSERT INTO onmaru.catalog_place_sources(id,place_id,provider,dataset,external_id,language,fetched_at)
                    VALUES (?,?,'kto-tourapi-korean','kto-korean-tour','4','ko-KR',now())
                    ON CONFLICT ON CONSTRAINT catalog_place_sources_provider_dataset_external_id_language_uq DO NOTHING
                    """, UUID.randomUUID(), legacyProposed);
        });
        race.countDown();
        assertThat(discovery.get()).isTrue(); legacy.get();
        UUID canonical = jdbc.queryForObject("SELECT place_id FROM onmaru.catalog_place_sources WHERE provider='kto-tourapi-korean' AND dataset='kto-korean-tour' AND external_id='4' AND language='ko-KR'", UUID.class);
        assertThat(jdbc.queryForObject("SELECT place_id FROM onmaru.selected_discovery_public_items WHERE revision_id=?", UUID.class, raceRun))
                .isEqualTo(canonical);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM onmaru.catalog_place_sources WHERE provider='kto-tourapi-korean' AND dataset='kto-korean-tour' AND external_id='4' AND language='ko-KR'", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT revision_id FROM onmaru.catalog_active_datasets WHERE dataset='kto-korean-tour'", UUID.class))
                .isEqualTo(legacyRevision);
    }

    private static boolean publishAfter(CountDownLatch start, JdbcSelectedDiscoveryStore store, UUID run,
                                        UUID expected, SelectedDiscoverySync.PublicItem item) {
        try { start.await(); return store.publish(run, expected, Map.of(item.contentId(), item), report(run)); }
        catch (InterruptedException exception) { Thread.currentThread().interrupt(); throw new IllegalStateException(exception); }
    }

    private static void stage(JdbcSelectedDiscoveryStore store, UUID run, SelectedDiscoverySync.PublicItem item, Instant due) {
        assertThat(store.claim(run, due)).isTrue();
        var row = item.row();
        var decision = new DiscoveryCandidatePolicy.Decision(DiscoveryCandidatePolicy.Status.INCLUDE, item.role(),
                "HUMAN_CONFIRMED", DiscoveryCandidatePolicy.VERSION, false);
        var candidate = new SelectedDiscoverySync.Candidate(item.contentId(), row, DiscoverySourceHash.list(row),
                DiscoverySourceHash.detail(row), DiscoverySourceHash.SCHEMA_VERSION, decision, SelectedDiscoverySync.Diff.ADDED, "same");
        store.stage(run, Map.of(item.contentId(), candidate), List.of(), report(run));
    }

    private static SelectedDiscoverySync.PublicItem item(String contentId) {
        var row = new SourceRecord("kto-tourapi-korean", "areaBasedList2", Map.of(
                "contentid", contentId, "lclsSystm3", "HS010100", "title", "궁궐", "overview", "검증된 상세",
                "mapx", "127.0", "mapy", "37.5", "areacode", "11"));
        var approval = new SelectedDiscoverySync.Approval(DiscoverySourceHash.list(row), DiscoverySourceHash.detail(row),
                DiscoveryCandidatePolicy.Role.CORE_TRADITIONAL_PLACE, DiscoveryCandidatePolicy.fingerprint(row),
                true, true, "https://example.org/review", "reviewer");
        return new SelectedDiscoverySync.PublicItem(contentId, row, approval.role(), approval);
    }

    private static SelectedDiscoverySync.Report report(UUID run) {
        return new SelectedDiscoverySync.Report(run, 1, 0, 0, 0, 0, 1, 1,
                Map.of("11", 1L), Map.of("CORE_TRADITIONAL_PLACE", 1L));
    }
}
