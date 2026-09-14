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

class JourneyMigrationTests {

    private static final int POSTGRES_PORT = 5432;
    private static final String DATABASE = "onmaru_test";
    private static final String USERNAME = "onmaru_test";
    private static final String PASSWORD = "onmaru_test";
    private static final String GUEST_TOKEN_HASH =
            "dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd";

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
    void migratesDiscoveryAndJourneyTables() throws Exception {
        resetAndMigrate();

        try (var connection = DriverManager.getConnection(jdbcUrl(), USERNAME, PASSWORD);
             var statement = connection.createStatement()) {
            assertThat(countRows(statement, """
                    SELECT COUNT(*)
                    FROM information_schema.tables
                    WHERE table_schema = 'onmaru'
                      AND table_name IN (
                        'discovery_explorations',
                        'discovery_runs',
                        'discovery_proposals',
                        'discovery_turns',
                        'journey_saved_journeys',
                        'journey_saved_resources'
                      )
                    """)).isEqualTo(6);
        }
    }

    @Test
    void enforcesExplorationOwnerXorAndActiveRunUniqueness() throws Exception {
        resetAndMigrate();
        var memberId = UUID.randomUUID();
        var guestId = UUID.randomUUID();
        var explorationId = UUID.randomUUID();
        var otherExplorationId = UUID.randomUUID();

        try (var connection = DriverManager.getConnection(jdbcUrl(), USERNAME, PASSWORD);
             var statement = connection.createStatement()) {
            insertMember(statement, memberId);
            insertGuest(statement, guestId);

            assertThatThrownBy(() -> statement.execute("""
                    INSERT INTO onmaru.discovery_explorations (
                        id, state_version, pinned_refs, excluded_refs, created_at, updated_at
                    ) VALUES (
                        gen_random_uuid(), 0, '[]'::jsonb, '[]'::jsonb,
                        CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
                    )
                    """))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("discovery_explorations_owner_xor_ck");

            assertThatThrownBy(() -> statement.execute("""
                    INSERT INTO onmaru.discovery_explorations (
                        id, owner_member_id, owner_guest_id, state_version,
                        pinned_refs, excluded_refs, created_at, updated_at
                    ) VALUES (
                        gen_random_uuid(), '%s', '%s', 0, '[]'::jsonb, '[]'::jsonb,
                        CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
                    )
                    """.formatted(memberId, guestId)))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("discovery_explorations_owner_xor_ck");

            insertExploration(statement, explorationId, memberId);
            insertExploration(statement, otherExplorationId, memberId);
            insertRun(statement, UUID.randomUUID(), explorationId, "actor:member:%s".formatted(memberId), "QUEUED", null);

            assertThatThrownBy(() -> insertRun(
                    statement,
                    UUID.randomUUID(),
                    explorationId,
                    "actor:member:other",
                    "QUEUED",
                    null
            ))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("discovery_runs_active_exploration_uq");

            assertThatThrownBy(() -> insertRun(
                    statement,
                    UUID.randomUUID(),
                    otherExplorationId,
                    "actor:member:%s".formatted(memberId),
                    "QUEUED",
                    null
            ))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("discovery_runs_active_actor_uq");
        }
    }

    @Test
    void terminalRunFreesActiveSlotAndVersionCasBlocksStaleUpdate() throws Exception {
        resetAndMigrate();
        var memberId = UUID.randomUUID();
        var explorationId = UUID.randomUUID();
        var runId = UUID.randomUUID();

        try (var connection = DriverManager.getConnection(jdbcUrl(), USERNAME, PASSWORD);
             var statement = connection.createStatement()) {
            insertMember(statement, memberId);
            insertExploration(statement, explorationId, memberId);
            insertRun(statement, runId, explorationId, "actor:member:%s".formatted(memberId), "RUNNING", null);

            statement.execute("""
                    UPDATE onmaru.discovery_runs
                    SET status = 'COMPLETED',
                        outcome = 'BOARD_READY'
                    WHERE id = '%s'
                    """.formatted(runId));
            insertRun(statement, UUID.randomUUID(), explorationId, "actor:member:%s".formatted(memberId), "QUEUED", null);

            assertThat(statement.executeUpdate("""
                    UPDATE onmaru.discovery_explorations
                    SET state_version = state_version + 1,
                        updated_at = CURRENT_TIMESTAMP
                    WHERE id = '%s'
                      AND state_version = 0
                    """.formatted(explorationId))).isEqualTo(1);

            assertThat(statement.executeUpdate("""
                    UPDATE onmaru.discovery_explorations
                    SET state_version = state_version + 1,
                        updated_at = CURRENT_TIMESTAMP
                    WHERE id = '%s'
                      AND state_version = 0
                    """.formatted(explorationId))).isZero();
        }
    }

    @Test
    void preventsDuplicateSavedJourneyAndSavedResourceForMember() throws Exception {
        resetAndMigrate();
        var memberId = UUID.randomUUID();
        var explorationId = UUID.randomUUID();
        var placeId = UUID.randomUUID();

        try (var connection = DriverManager.getConnection(jdbcUrl(), USERNAME, PASSWORD);
             var statement = connection.createStatement()) {
            insertMember(statement, memberId);
            insertExploration(statement, explorationId, memberId);
            statement.execute("""
                    INSERT INTO onmaru.catalog_place_identity (id, created_at)
                    VALUES ('%s', CURRENT_TIMESTAMP)
                    """.formatted(placeId));
            insertSavedJourney(statement, UUID.randomUUID(), memberId, explorationId, 1);
            insertSavedResource(statement, UUID.randomUUID(), memberId, "PLACE", placeId);

            assertThatThrownBy(() -> insertSavedJourney(statement, UUID.randomUUID(), memberId, explorationId, 1))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("journey_saved_journeys_member_source_version_uq");

            assertThatThrownBy(() -> insertSavedResource(statement, UUID.randomUUID(), memberId, "PLACE", placeId))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("journey_saved_resources_member_resource_uq");
        }
    }

    private static void insertMember(Statement statement, UUID memberId) throws Exception {
        statement.execute("""
                INSERT INTO onmaru.identity_members (id, status, created_at)
                VALUES ('%s', 'ACTIVE', CURRENT_TIMESTAMP)
                """.formatted(memberId));
    }

    private static void insertGuest(Statement statement, UUID guestId) throws Exception {
        statement.execute("""
                INSERT INTO onmaru.identity_guests (id, token_hash, expires_at)
                VALUES ('%s', '%s', CURRENT_TIMESTAMP + interval '1 hour')
                """.formatted(guestId, GUEST_TOKEN_HASH));
    }

    private static void insertExploration(Statement statement, UUID explorationId, UUID memberId) throws Exception {
        statement.execute("""
                INSERT INTO onmaru.discovery_explorations (
                    id, owner_member_id, state_version, pinned_refs, excluded_refs,
                    created_at, updated_at
                ) VALUES (
                    '%s', '%s', 0, '[]'::jsonb, '[]'::jsonb,
                    CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
                )
                """.formatted(explorationId, memberId));
    }

    private static void insertRun(
            Statement statement,
            UUID runId,
            UUID explorationId,
            String actorKey,
            String status,
            String outcome
    ) throws Exception {
        var outcomeValue = outcome == null ? "NULL" : "'%s'".formatted(outcome);
        statement.execute("""
                INSERT INTO onmaru.discovery_runs (
                    id, exploration_id, actor_key, base_version, status, outcome,
                    created_at, deadline_at, generation, engine
                ) VALUES (
                    '%s', '%s', '%s', 0, '%s', %s,
                    CURRENT_TIMESTAMP, CURRENT_TIMESTAMP + interval '20 seconds', 1, 'BASELINE'
                )
                """.formatted(runId, explorationId, actorKey, status, outcomeValue));
    }

    private static void insertSavedJourney(
            Statement statement,
            UUID savedJourneyId,
            UUID memberId,
            UUID explorationId,
            int sourceVersion
    ) throws Exception {
        statement.execute("""
                INSERT INTO onmaru.journey_saved_journeys (
                    id, member_id, source_exploration_id, source_version,
                    saved_at, title, snapshot, snapshot_hash
                ) VALUES (
                    '%s', '%s', '%s', %d, CURRENT_TIMESTAMP,
                    '전주 한옥 산책', '{"schemaVersion":"1.2"}'::jsonb, 'snapshot-hash'
                )
                """.formatted(savedJourneyId, memberId, explorationId, sourceVersion));
    }

    private static void insertSavedResource(
            Statement statement,
            UUID savedResourceId,
            UUID memberId,
            String resourceType,
            UUID resourceId
    ) throws Exception {
        statement.execute("""
                INSERT INTO onmaru.journey_saved_resources (
                    id, member_id, resource_type, resource_id, saved_at
                ) VALUES (
                    '%s', '%s', '%s', '%s', CURRENT_TIMESTAMP
                )
                """.formatted(savedResourceId, memberId, resourceType, resourceId));
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
