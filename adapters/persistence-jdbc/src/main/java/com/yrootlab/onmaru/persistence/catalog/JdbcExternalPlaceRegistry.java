package com.yrootlab.onmaru.persistence.catalog;

import com.yrootlab.onmaru.catalog.externalplace.ExternalPlace;
import com.yrootlab.onmaru.catalog.externalplace.ExternalPlaceCandidate;
import com.yrootlab.onmaru.catalog.externalplace.ExternalPlaceIdentityConflictException;
import com.yrootlab.onmaru.catalog.externalplace.ExternalPlacePolicy;
import com.yrootlab.onmaru.catalog.externalplace.ExternalPlaceRegistry;
import com.yrootlab.onmaru.persistence.jdbc.JdbcTransactionRunner;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

public final class JdbcExternalPlaceRegistry implements ExternalPlaceRegistry {

    private static final double LOCATION_CONFLICT_METERS = 1_000.0;

    private final JdbcTransactionRunner transactions;
    private final ExternalPlacePolicy policy;
    private final Supplier<UUID> idGenerator;

    public JdbcExternalPlaceRegistry(
            JdbcTransactionRunner transactions,
            ExternalPlacePolicy policy,
            Supplier<UUID> idGenerator) {
        this.transactions = transactions;
        this.policy = policy;
        this.idGenerator = idGenerator;
    }

    @Override
    public ExternalPlace resolveOrCreate(ExternalPlaceCandidate rawCandidate, String regionCode) {
        var candidate = policy.validate(rawCandidate);
        if (regionCode == null || regionCode.isBlank()) {
            throw new IllegalArgumentException("regionCode is required");
        }
        return transactions.execute(connection -> resolveOrCreate(connection, candidate, regionCode));
    }

    private ExternalPlace resolveOrCreate(
            Connection connection,
            ExternalPlaceCandidate candidate,
            String regionCode) {
        try {
            lockIdentity(connection, candidate);
            var existing = find(connection, candidate);
            if (existing.isPresent()) {
                if (existing.get().distanceMeters() > LOCATION_CONFLICT_METERS) {
                    throw new ExternalPlaceIdentityConflictException();
                }
                return existing.get().place();
            }
            return insert(connection, candidate, regionCode);
        } catch (ExternalPlaceIdentityConflictException exception) {
            throw exception;
        } catch (SQLException exception) {
            throw new IllegalStateException("External place registry database operation failed", exception);
        }
    }

    private void lockIdentity(Connection connection, ExternalPlaceCandidate candidate) throws SQLException {
        try (var statement = connection.prepareStatement(
                "SELECT pg_advisory_xact_lock(hashtextextended(?, 0))")) {
            statement.setString(1, candidate.provider().name() + ":" + candidate.externalId());
            statement.executeQuery().close();
        }
    }

    private Optional<StoredPlace> find(Connection connection, ExternalPlaceCandidate candidate) throws SQLException {
        try (var statement = connection.prepareStatement("""
                SELECT place_id, public_place_id, name, region_code,
                       ST_Y(location::geometry) AS latitude,
                       ST_X(location::geometry) AS longitude,
                       ST_Distance(
                           location,
                           ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography
                       ) AS distance_meters
                FROM onmaru.catalog_external_places
                WHERE provider = ? AND external_id = ?
                """)) {
            statement.setDouble(1, candidate.lng());
            statement.setDouble(2, candidate.lat());
            statement.setString(3, candidate.provider().name());
            statement.setString(4, candidate.externalId());
            try (var result = statement.executeQuery()) {
                return result.next() ? Optional.of(read(result)) : Optional.empty();
            }
        }
    }

    private StoredPlace read(ResultSet result) throws SQLException {
        return new StoredPlace(new ExternalPlace(
                result.getObject("place_id", UUID.class),
                result.getString("public_place_id"),
                result.getString("name"),
                result.getString("region_code"),
                result.getDouble("latitude"),
                result.getDouble("longitude"),
                false), result.getDouble("distance_meters"));
    }

    private ExternalPlace insert(
            Connection connection,
            ExternalPlaceCandidate candidate,
            String regionCode) throws SQLException {
        var placeId = idGenerator.get();
        var publicPlaceId = "p-ext-" + placeId.toString().replace("-", "");
        var createdAt = OffsetDateTime.now(ZoneOffset.UTC);
        try (var identity = connection.prepareStatement("""
                INSERT INTO onmaru.catalog_place_identity (id, created_at)
                VALUES (?, ?)
                """)) {
            identity.setObject(1, placeId);
            identity.setObject(2, createdAt);
            identity.executeUpdate();
        }
        try (var publicId = connection.prepareStatement("""
                INSERT INTO onmaru.catalog_place_public_ids (public_id, place_id, created_at)
                VALUES (?, ?, ?)
                """)) {
            publicId.setString(1, publicPlaceId);
            publicId.setObject(2, placeId);
            publicId.setObject(3, createdAt);
            publicId.executeUpdate();
        }
        try (var external = connection.prepareStatement("""
                INSERT INTO onmaru.catalog_external_places (
                    provider, external_id, place_id, public_place_id, name, region_code,
                    location, provenance, created_at
                ) VALUES (?, ?, ?, ?, ?, ?,
                          ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography,
                          'CLIENT_ASSERTED', ?)
                """)) {
            external.setString(1, candidate.provider().name());
            external.setString(2, candidate.externalId());
            external.setObject(3, placeId);
            external.setString(4, publicPlaceId);
            external.setString(5, candidate.name());
            external.setString(6, regionCode);
            external.setDouble(7, candidate.lng());
            external.setDouble(8, candidate.lat());
            external.setObject(9, createdAt);
            external.executeUpdate();
        }
        return new ExternalPlace(placeId, publicPlaceId, candidate.name(), regionCode,
                candidate.lat(), candidate.lng(), true);
    }

    private record StoredPlace(ExternalPlace place, double distanceMeters) {
    }
}
