package com.yrootlab.onmaru.persistence.insights;

import com.yrootlab.onmaru.insights.ingestion.DataLabCollectionGuard;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Objects;
import java.util.Optional;

/** Holds a PostgreSQL transaction advisory lock for the entire fetch-and-publish operation. */
public final class JdbcDataLabCollectionGuard implements DataLabCollectionGuard {

    private static final long LOCK_KEY = 0x4f4e4d415255444cL;

    private final DataSource dataSource;

    public JdbcDataLabCollectionGuard(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource);
    }

    @Override
    public Optional<Lease> tryAcquire() {
        Connection connection = null;
        try {
            connection = dataSource.getConnection();
            connection.setAutoCommit(false);
            try (var statement = connection.prepareStatement("SELECT pg_try_advisory_xact_lock(?)")) {
                statement.setLong(1, LOCK_KEY);
                try (var result = statement.executeQuery()) {
                    result.next();
                    if (!result.getBoolean(1)) {
                        close(connection);
                        return Optional.empty();
                    }
                }
            }
            Connection lockedConnection = connection;
            return Optional.of(() -> close(lockedConnection));
        } catch (SQLException exception) {
            close(connection);
            throw new IllegalStateException("could not acquire DataLab collection lock", exception);
        }
    }

    private static void close(Connection connection) {
        if (connection == null) {
            return;
        }
        try {
            connection.rollback();
        } catch (SQLException ignored) {
            // Closing the physical connection also releases a transaction-scoped advisory lock.
        }
        try {
            connection.close();
        } catch (SQLException exception) {
            throw new IllegalStateException("could not release DataLab collection lock", exception);
        }
    }
}
