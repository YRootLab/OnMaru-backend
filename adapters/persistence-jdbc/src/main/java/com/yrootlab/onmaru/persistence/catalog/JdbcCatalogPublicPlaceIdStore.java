package com.yrootlab.onmaru.persistence.catalog;

import com.yrootlab.onmaru.catalog.publicid.CatalogPublicPlaceIdConflictException;
import com.yrootlab.onmaru.catalog.publicid.CatalogPublicPlaceIdStore;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/** PostgreSQL implementation of the Catalog-owned, immutable public place-ID mapping. */
public final class JdbcCatalogPublicPlaceIdStore implements CatalogPublicPlaceIdStore {

    private static final Pattern PUBLIC_PLACE_ID = Pattern.compile("p-[a-z0-9]+(?:-[a-z0-9]+)*");

    private final DataSource dataSource;

    public JdbcCatalogPublicPlaceIdStore(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public Optional<UUID> findPlaceId(String publicPlaceId) {
        var normalizedPublicPlaceId = requirePublicPlaceId(publicPlaceId);
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement("""
                     SELECT place_id
                     FROM onmaru.catalog_place_public_ids
                     WHERE public_id = ?
                     """)) {
            statement.setString(1, normalizedPublicPlaceId);
            try (var result = statement.executeQuery()) {
                return result.next() ? Optional.of(result.getObject("place_id", UUID.class)) : Optional.empty();
            }
        } catch (SQLException exception) {
            throw databaseFailure(exception);
        }
    }

    @Override
    public void register(String publicPlaceId, UUID placeId) {
        var normalizedPublicPlaceId = requirePublicPlaceId(publicPlaceId);
        if (placeId == null) {
            throw new IllegalArgumentException("placeId is required");
        }
        try (var connection = dataSource.getConnection()) {
            try (var insert = connection.prepareStatement("""
                    INSERT INTO onmaru.catalog_place_public_ids (public_id, place_id)
                    VALUES (?, ?)
                    ON CONFLICT (public_id) DO NOTHING
                    """)) {
                insert.setString(1, normalizedPublicPlaceId);
                insert.setObject(2, placeId);
                if (insert.executeUpdate() == 1) {
                    return;
                }
            }
            if (!findPlaceId(normalizedPublicPlaceId).filter(placeId::equals).isPresent()) {
                throw new CatalogPublicPlaceIdConflictException("public place ID is already mapped to another Catalog place");
            }
        } catch (CatalogPublicPlaceIdConflictException exception) {
            throw exception;
        } catch (SQLException exception) {
            if (findPlaceId(normalizedPublicPlaceId).filter(placeId::equals).isPresent()) {
                return;
            }
            throw new CatalogPublicPlaceIdConflictException(
                    "public place ID or Catalog place is already mapped", exception);
        }
    }

    private String requirePublicPlaceId(String rawPublicPlaceId) {
        if (rawPublicPlaceId == null || !PUBLIC_PLACE_ID.matcher(rawPublicPlaceId).matches()) {
            throw new IllegalArgumentException("publicPlaceId must follow the p-lowercase-kebab-case format");
        }
        return rawPublicPlaceId;
    }

    private IllegalStateException databaseFailure(SQLException exception) {
        return new IllegalStateException("Catalog public place-ID database operation failed", exception);
    }
}
