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
            statement.execute("DROP SCHEMA IF EXISTS public CASCADE");
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
