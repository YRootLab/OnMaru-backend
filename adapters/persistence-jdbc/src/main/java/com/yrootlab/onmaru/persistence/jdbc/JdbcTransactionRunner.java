package com.yrootlab.onmaru.persistence.jdbc;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.Objects;
import java.util.function.Function;

/** Shares one JDBC transaction across collaborating persistence adapters on the request thread. */
public final class JdbcTransactionRunner {

    private final DataSource dataSource;
    private final ThreadLocal<Connection> current = new ThreadLocal<>();

    public JdbcTransactionRunner(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    public <T> T execute(Function<Connection, T> operation) {
        var existing = current.get();
        if (existing != null) {
            return operation.apply(existing);
        }
        try (var connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            current.set(connection);
            try {
                T result = operation.apply(connection);
                connection.commit();
                return result;
            } catch (RuntimeException exception) {
                rollback(connection);
                throw exception;
            } catch (Exception exception) {
                rollback(connection);
                throw new IllegalStateException("JDBC transaction failed", exception);
            } finally {
                current.remove();
            }
        } catch (RuntimeException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalStateException("JDBC transaction failed", exception);
        }
    }

    private void rollback(Connection connection) {
        try { connection.rollback(); } catch (Exception ignored) { }
    }
}
