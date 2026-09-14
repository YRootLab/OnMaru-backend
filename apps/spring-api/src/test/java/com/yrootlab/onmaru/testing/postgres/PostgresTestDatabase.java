package com.yrootlab.onmaru.testing.postgres;

import java.sql.Connection;
import java.sql.SQLException;

public final class PostgresTestDatabase {

    private PostgresTestDatabase() {
    }

    public static void reset(Connection connection) throws SQLException {
        var originalAutoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);

        try (var statement = connection.createStatement()) {
            statement.execute("DROP SCHEMA IF EXISTS onmaru CASCADE");
            statement.execute("DROP SCHEMA IF EXISTS onmaru_registry CASCADE");
            statement.execute("DROP SCHEMA IF EXISTS public CASCADE");
            statement.execute("DROP ROLE IF EXISTS onmaru_runtime_login");
            statement.execute("DROP ROLE IF EXISTS onmaru_readonly_login");
            statement.execute("""
                    DO $$
                    BEGIN
                        IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'onmaru_backup') THEN
                            REVOKE pg_read_all_data FROM onmaru_backup;
                        END IF;
                    END
                    $$;
                    """);
            statement.execute("DROP ROLE IF EXISTS onmaru_backup");
            statement.execute("DROP ROLE IF EXISTS onmaru_readonly");
            statement.execute("DROP ROLE IF EXISTS onmaru_runtime");
            statement.execute("DROP ROLE IF EXISTS onmaru_migration");
            statement.execute("CREATE SCHEMA public");
            statement.execute("GRANT ALL ON SCHEMA public TO public");
            statement.execute("CREATE EXTENSION IF NOT EXISTS postgis");
            connection.commit();
        } catch (SQLException exception) {
            connection.rollback();
            throw exception;
        } finally {
            connection.setAutoCommit(originalAutoCommit);
        }
    }
}
