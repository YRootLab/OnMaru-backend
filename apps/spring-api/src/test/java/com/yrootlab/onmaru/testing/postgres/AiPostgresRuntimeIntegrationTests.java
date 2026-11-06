package com.yrootlab.onmaru.testing.postgres;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AiPostgresRuntimeIntegrationTests {

    private static final int POSTGRES_PORT = 5432;
    private static final String DATABASE = "onmaru_test";
    private static final String BOOTSTRAP_USER = "onmaru_test";
    private static final String BOOTSTRAP_PASSWORD = "onmaru_test";

    private static final String AI_RUNTIME_USER = "onmaru_ai_runtime_login";
    private static final String AI_RUNTIME_PASSWORD = "ai_runtime_secret_password";
    private static final String AI_MIGRATION_USER = "onmaru_ai_migration_login";
    private static final String AI_MIGRATION_PASSWORD = "ai_migration_secret_password";

    // Use verified pgvector image for testing vector extension & ai schema isolation
    private static final GenericContainer<?> postgres = new GenericContainer<>(
            DockerImageName.parse("pgvector/pgvector:0.8.0-pg17"))
            .withExposedPorts(POSTGRES_PORT)
            .withEnv("POSTGRES_DB", DATABASE)
            .withEnv("POSTGRES_USER", BOOTSTRAP_USER)
            .withEnv("POSTGRES_PASSWORD", BOOTSTRAP_PASSWORD)
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
    void bootstrapAiRuntimeIsIdempotentAndEnforcesLeastPrivileges() throws Exception {
        try (var connection = connect(BOOTSTRAP_USER, BOOTSTRAP_PASSWORD)) {
            // Run bootstrap script logic twice to verify idempotency
            applyAiBootstrap(connection);
            applyAiBootstrap(connection);

            // Verify vector extension is installed and functional
            try (var statement = connection.createStatement();
                 var rs = statement.executeQuery("SELECT extname, extversion FROM pg_extension WHERE extname = 'vector'")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString("extname")).isEqualTo("vector");
                assertThat(rs.getString("extversion")).isNotBlank();
            }

            // Verify roles and ai schema exist
            try (var statement = connection.createStatement();
                 var rs = statement.executeQuery("""
                         SELECT COUNT(*) FROM pg_roles
                         WHERE rolname IN ('onmaru_ai_runtime', 'onmaru_ai_migration',
                                           'onmaru_ai_runtime_login', 'onmaru_ai_migration_login')
                         """)) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getInt(1)).isEqualTo(4);
            }

            try (var statement = connection.createStatement();
                 var rs = statement.executeQuery("SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name = 'ai'")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getInt(1)).isEqualTo(1);
            }
        }

        // Test Migration Role: can execute DDL and DML in ai schema
        try (var migrationConn = connect(AI_MIGRATION_USER, AI_MIGRATION_PASSWORD);
             var statement = migrationConn.createStatement()) {

            statement.execute("""
                    CREATE TABLE ai.corpus_embeddings (
                        id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
                        embedding vector(3) NOT NULL,
                        content text NOT NULL
                    )
                    """);

            statement.execute("""
                    INSERT INTO ai.corpus_embeddings (embedding, content)
                    VALUES ('[0.1, 0.2, 0.3]'::vector(3), 'Test Korean Heritage Hanok Content')
                    """);

            // Migration role cannot create tables in public schema
            assertThatThrownBy(() -> statement.execute("CREATE TABLE public.unauthorized_tbl (id int)"))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("permission denied");
        }

        // Test AI Runtime Role: least privilege in ai schema only, blocked from DDL and public writes
        try (var runtimeConn = connect(AI_RUNTIME_USER, AI_RUNTIME_PASSWORD);
             var statement = runtimeConn.createStatement()) {

            // Can read from ai schema
            try (var rs = statement.executeQuery("SELECT COUNT(*), content FROM ai.corpus_embeddings GROUP BY content")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getInt(1)).isEqualTo(1);
                assertThat(rs.getString("content")).isEqualTo("Test Korean Heritage Hanok Content");
            }

            // Can perform vector distance computation
            try (var rs = statement.executeQuery("""
                    SELECT embedding <=> '[0.1, 0.2, 0.3]'::vector(3) AS cosine_dist
                    FROM ai.corpus_embeddings
                    """)) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getDouble("cosine_dist")).isCloseTo(0.0, org.assertj.core.data.Offset.offset(0.001));
            }

            // Can insert/update/delete in ai schema
            statement.execute("""
                    INSERT INTO ai.corpus_embeddings (embedding, content)
                    VALUES ('[0.4, 0.5, 0.6]'::vector(3), 'Runtime inserted chunk')
                    """);
            statement.execute("DELETE FROM ai.corpus_embeddings WHERE content = 'Runtime inserted chunk'");

            // CANNOT execute DDL in ai schema
            assertThatThrownBy(() -> statement.execute("CREATE TABLE ai.runtime_illegal_ddl (id int)"))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("permission denied");

            // CANNOT write or create tables in public schema
            assertThatThrownBy(() -> statement.execute("CREATE TABLE public.runtime_public_write (id int)"))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("permission denied");

            // CANNOT write into public schema tables
            statement.getConnection().createStatement().close();
        }

        // Verify that public schema write is denied even if a table exists in public
        try (var bootstrapConn = connect(BOOTSTRAP_USER, BOOTSTRAP_PASSWORD);
             var statement = bootstrapConn.createStatement()) {
            statement.execute("CREATE TABLE public.business_orders (id int PRIMARY KEY, note text)");
        }

        try (var runtimeConn = connect(AI_RUNTIME_USER, AI_RUNTIME_PASSWORD);
             var statement = runtimeConn.createStatement()) {
            assertThatThrownBy(() -> statement.execute("INSERT INTO public.business_orders (id, note) VALUES (1, 'leak')"))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("permission denied");
        }
    }

    private static void applyAiBootstrap(Connection connection) throws SQLException {
        try (var statement = connection.createStatement()) {
            statement.execute("CREATE EXTENSION IF NOT EXISTS vector;");
            statement.execute("CREATE SCHEMA IF NOT EXISTS ai;");
            statement.execute("""
                    DO $$
                    BEGIN
                        IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'onmaru_ai_runtime') THEN
                            CREATE ROLE onmaru_ai_runtime NOLOGIN;
                        ELSE
                            ALTER ROLE onmaru_ai_runtime NOLOGIN;
                        END IF;
                        IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'onmaru_ai_migration') THEN
                            CREATE ROLE onmaru_ai_migration NOLOGIN;
                        ELSE
                            ALTER ROLE onmaru_ai_migration NOLOGIN;
                        END IF;
                    END
                    $$;
                    """);

            statement.execute("""
                    CREATE OR REPLACE FUNCTION pg_temp.ensure_login_role(role_name text, role_pass text) RETURNS void AS $f$
                    BEGIN
                        IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = role_name) THEN
                            EXECUTE format('CREATE ROLE %I LOGIN PASSWORD %L', role_name, role_pass);
                        ELSE
                            EXECUTE format('ALTER ROLE %I WITH LOGIN PASSWORD %L', role_name, role_pass);
                        END IF;
                    END;
                    $f$ LANGUAGE plpgsql;
                    """);

            statement.execute("SELECT pg_temp.ensure_login_role('" + AI_RUNTIME_USER + "', '" + AI_RUNTIME_PASSWORD + "');");
            statement.execute("SELECT pg_temp.ensure_login_role('" + AI_MIGRATION_USER + "', '" + AI_MIGRATION_PASSWORD + "');");

            statement.execute("GRANT onmaru_ai_runtime TO " + AI_RUNTIME_USER + ";");
            statement.execute("GRANT onmaru_ai_migration TO " + AI_MIGRATION_USER + ";");

            statement.execute("REVOKE ALL ON SCHEMA public FROM onmaru_ai_runtime, onmaru_ai_migration;");
            statement.execute("REVOKE CREATE ON SCHEMA public FROM PUBLIC;");

            statement.execute("GRANT USAGE ON SCHEMA ai TO onmaru_ai_runtime;");
            statement.execute("GRANT USAGE, CREATE ON SCHEMA ai TO onmaru_ai_migration;");

            statement.execute("""
                    ALTER DEFAULT PRIVILEGES IN SCHEMA ai
                        GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO onmaru_ai_runtime;
                    ALTER DEFAULT PRIVILEGES IN SCHEMA ai
                        GRANT USAGE, SELECT, UPDATE ON SEQUENCES TO onmaru_ai_runtime;
                    """);

            statement.execute("ALTER DEFAULT PRIVILEGES FOR ROLE " + AI_MIGRATION_USER + " IN SCHEMA ai GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO onmaru_ai_runtime;");
            statement.execute("ALTER DEFAULT PRIVILEGES FOR ROLE " + AI_MIGRATION_USER + " IN SCHEMA ai GRANT USAGE, SELECT, UPDATE ON SEQUENCES TO onmaru_ai_runtime;");
        }
    }

    private static Connection connect(String user, String pass) throws SQLException {
        return DriverManager.getConnection(jdbcUrl(), user, pass);
    }

    private static String jdbcUrl() {
        return "jdbc:postgresql://%s:%d/%s".formatted(
                postgres.getHost(),
                postgres.getMappedPort(POSTGRES_PORT),
                DATABASE);
    }
}
