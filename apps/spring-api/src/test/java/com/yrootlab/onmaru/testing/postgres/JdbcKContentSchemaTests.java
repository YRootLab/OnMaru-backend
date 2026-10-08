package com.yrootlab.onmaru.testing.postgres;

import com.yrootlab.onmaru.persistence.kcontents.JdbcKContentRelationRepository;
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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JdbcKContentSchemaTests {
    private static final GenericContainer<?> POSTGRES = new GenericContainer<>(
            DockerImageName.parse("postgis/postgis:17-3.5-alpine"))
            .withExposedPorts(5432).withEnv("POSTGRES_DB", "onmaru_test")
            .withEnv("POSTGRES_USER", "onmaru_test").withEnv("POSTGRES_PASSWORD", "onmaru_test")
            .waitingFor(Wait.forLogMessage(".*database system is ready to accept connections.*\\n", 2));

    @BeforeAll static void start() { POSTGRES.start(); }
    @AfterAll static void stop() { POSTGRES.stop(); }

    @Test void migrationKeysPublicGateAndRollback() throws Exception {
        var url = "jdbc:postgresql://" + POSTGRES.getHost() + ":" + POSTGRES.getMappedPort(5432) + "/onmaru_test";
        try (var connection = DriverManager.getConnection(url, "onmaru_test", "onmaru_test")) {
            PostgresTestDatabase.reset(connection);
        }
        Flyway.configure().dataSource(url, "onmaru_test", "onmaru_test")
                .locations("classpath:db/migration/baseline").baselineOnMigrate(true).baselineVersion("0")
                .load().migrate();
        var ds = new DriverManagerDataSource(url, "onmaru_test", "onmaru_test");
        var jdbc = new JdbcTemplate(ds);
        var repository = new JdbcKContentRelationRepository(ds);
        var place1 = UUID.randomUUID(); var place2 = UUID.randomUUID();
        var work1 = UUID.randomUUID(); var work2 = UUID.randomUUID();
        for (var place : new UUID[]{place1, place2})
            jdbc.update("INSERT INTO onmaru.catalog_place_identity (id, created_at) VALUES (?, now())", place);
        for (var work : new UUID[]{work1, work2})
            jdbc.update("INSERT INTO onmaru.k_contents (id,title,normalized_title,work_type,status,verified_by,verified_at) VALUES (?,?,?,?,?,'test',now())",
                    work, "작품", "작품", "DRAMA", "HUMAN_VERIFIED");
        var relation1 = UUID.randomUUID(); var relation2 = UUID.randomUUID(); var relation3 = UUID.randomUUID();
        insertRelation(jdbc, relation1, place1, work1);
        insertRelation(jdbc, relation2, place1, work2);
        insertRelation(jdbc, relation3, place2, work1);
        assertThatThrownBy(() -> insertRelation(jdbc, UUID.randomUUID(), place1, work1)).hasMessageContaining("unique");
        assertThatThrownBy(() -> insertRelation(jdbc, UUID.randomUUID(), UUID.randomUUID(), work1)).hasMessageContaining("foreign key");
        assertThat(repository.findPublicByPlace(place1)).isEmpty();
        var evidence = UUID.randomUUID();
        jdbc.update("INSERT INTO onmaru.k_content_relation_evidence (id,relation_id,canonical_url,source_type,title,filming_excerpt,observed_at,status,verified_by,verified_at) VALUES (?,?,?,?,?,?,now(),?,'test',now())",
                evidence, relation1, "https://example.org/filming", "OFFICIAL", "촬영 공지", "장소에서 촬영했다", "VERIFIED");
        assertThat(repository.findPublicByPlace(place1)).hasSize(1);
        assertThat(repository.findPublicByWork(work1)).hasSize(1);
        assertThatThrownBy(() -> jdbc.update("INSERT INTO onmaru.k_content_relation_evidence (id,relation_id,canonical_url,source_type,title,filming_excerpt,observed_at) VALUES (?,?,?,?,?,?,now())",
                UUID.randomUUID(), relation1, "https://example.org/filming", "OFFICIAL", "중복", "촬영")).hasMessageContaining("unique");
        jdbc.update("UPDATE onmaru.k_content_relation_evidence SET status='WITHDRAWN' WHERE id=?", evidence);
        assertThat(repository.findPublicByPlace(place1)).isEmpty();
        jdbc.update("UPDATE onmaru.k_content_relation_evidence SET status='VERIFIED' WHERE id=?", evidence);
        jdbc.update("UPDATE onmaru.k_content_place_relations SET status='STALE' WHERE id=?", relation1);
        assertThat(repository.findPublicByWork(work1)).isEmpty();
        // A failed transaction never publishes partially submitted evidence.
        assertThatThrownBy(() -> {
            try (var connection = ds.getConnection()) {
                connection.setAutoCommit(false);
                try (var statement = connection.createStatement()) {
                    statement.execute("UPDATE onmaru.k_content_place_relations SET status='HUMAN_VERIFIED', verified_by='test', verified_at=now() WHERE id='" + relation1 + "'");
                    statement.execute("INSERT INTO onmaru.k_content_relation_evidence(id,relation_id,canonical_url,source_type,title,filming_excerpt,observed_at) VALUES ('" + UUID.randomUUID() + "','" + relation1 + "','https://example.org/filming','OFFICIAL','중복','촬영',now())");
                    connection.commit();
                } catch (Exception e) { connection.rollback(); throw e; }
            }
        }).isInstanceOf(Exception.class);
        assertThat(repository.findPublicByPlace(place1)).isEmpty();
    }

    private static void insertRelation(JdbcTemplate jdbc, UUID id, UUID place, UUID work) {
        jdbc.update("INSERT INTO onmaru.k_content_place_relations (id,place_id,k_content_id,status,verified_by,verified_at) VALUES (?,?,?,'HUMAN_VERIFIED','test',now())", id, place, work);
    }
}
