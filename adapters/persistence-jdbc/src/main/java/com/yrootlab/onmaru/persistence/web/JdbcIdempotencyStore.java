package com.yrootlab.onmaru.persistence.web;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.yrootlab.onmaru.web.common.idempotency.IdempotencyCommand;
import com.yrootlab.onmaru.web.common.idempotency.IdempotencyConflictException;
import com.yrootlab.onmaru.web.common.idempotency.IdempotencyStorePort;
import com.yrootlab.onmaru.web.common.idempotency.IdempotentResponse;
import com.yrootlab.onmaru.persistence.jdbc.JdbcTransactionRunner;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.function.Supplier;

/** PostgreSQL-backed HTTP idempotency receipt store. */
public final class JdbcIdempotencyStore implements IdempotencyStorePort {

    private final DataSource dataSource;
    private final JdbcTransactionRunner transactions;
    private final ObjectMapper mapper = new ObjectMapper()
            .findAndRegisterModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    public JdbcIdempotencyStore(DataSource dataSource) {
        this(dataSource, new JdbcTransactionRunner(dataSource));
    }

    public JdbcIdempotencyStore(DataSource dataSource, JdbcTransactionRunner transactions) {
        this.dataSource = dataSource;
        this.transactions = transactions;
    }

    @Override
    public IdempotentResponse execute(
            IdempotencyCommand command,
            Clock clock,
            Supplier<IdempotentResponse> handler) {
        return transactions.execute(connection -> {
            try {
                lockReceipt(connection, command);
                var stored = find(connection, command);
                if (stored != null) {
                    if (!stored.fingerprint().equals(command.payloadFingerprint())) {
                        throw new IdempotencyConflictException();
                    }
                    return stored.response();
                }

                var response = handler.get();
                insert(connection, command, response, clock);
                return response;
            } catch (RuntimeException exception) {
                throw exception;
            } catch (Exception exception) {
                throw new IllegalStateException("Idempotency receipt database operation failed", exception);
            }
        });
    }

    private void lockReceipt(Connection connection, IdempotencyCommand command) throws Exception {
        try (var statement = connection.prepareStatement(
                "SELECT pg_advisory_xact_lock(hashtext(?))")) {
            statement.setString(1, receiptLockKey(command));
            statement.execute();
        }
    }

    private String receiptLockKey(IdempotencyCommand command) {
        return String.join("|", command.subjectId(), command.key().toString(), command.method(), command.path());
    }

    private void insert(
            Connection connection,
            IdempotencyCommand command,
            IdempotentResponse response,
            Clock clock) throws Exception {
        try (var statement = connection.prepareStatement("""
                INSERT INTO onmaru.web_idempotency_receipts
                    (subject_id, idempotency_key, method, path, payload_fingerprint,
                     response_status, response_headers, response_body, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?)
                """)) {
            statement.setString(1, command.subjectId());
            statement.setObject(2, command.key());
            statement.setString(3, command.method());
            statement.setString(4, command.path());
            statement.setString(5, command.payloadFingerprint());
            statement.setInt(6, response.status());
            statement.setString(7, mapper.writeValueAsString(response.headers()));
            statement.setString(8, mapper.writeValueAsString(response.body()));
            statement.setObject(9, OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC));
            statement.executeUpdate();
        }
    }

    private StoredReceipt find(Connection connection, IdempotencyCommand command) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT payload_fingerprint, response_status, response_headers, response_body
                FROM onmaru.web_idempotency_receipts
                WHERE subject_id = ? AND idempotency_key = ? AND method = ? AND path = ?
                """)) {
            statement.setString(1, command.subjectId());
            statement.setObject(2, command.key());
            statement.setString(3, command.method());
            statement.setString(4, command.path());
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    return null;
                }
                return new StoredReceipt(
                        result.getString("payload_fingerprint"),
                        new IdempotentResponse(
                                result.getInt("response_status"),
                                mapper.readValue(result.getString("response_headers"),
                                        new TypeReference<Map<String, String>>() { }),
                                mapper.readValue(result.getString("response_body"), Object.class)));
            }
        }
    }

    private record StoredReceipt(String fingerprint, IdempotentResponse response) { }
}
