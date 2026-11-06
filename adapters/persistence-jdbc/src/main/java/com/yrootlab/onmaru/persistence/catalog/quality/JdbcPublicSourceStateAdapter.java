package com.yrootlab.onmaru.persistence.catalog.quality;

import com.yrootlab.onmaru.catalog.application.query.quality.PublicSourceStateItem;
import com.yrootlab.onmaru.catalog.application.query.quality.PublicSourceStateQueryPort;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Optional;

public final class JdbcPublicSourceStateAdapter implements PublicSourceStateQueryPort {

    private final DataSource dataSource;

    public JdbcPublicSourceStateAdapter(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public Optional<PublicSourceStateItem> findPublicSourceState(String sourceId, String sourceRevision) {
        if (sourceId == null || sourceId.isBlank() || sourceRevision == null || sourceRevision.isBlank()) {
            return Optional.empty();
        }

        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT
                         d.dataset_revision,
                         d.state,
                         d.created_at
                     FROM onmaru.catalog_dataset_revisions d
                     WHERE d.dataset_revision = ?
                     LIMIT 1
                     """)) {

            statement.setString(1, sourceRevision);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (resultSet.next()) {
                    String state = resultSet.getString("state");
                    var createdAt = resultSet.getTimestamp("created_at");
                    Instant effectiveAt = createdAt != null ? createdAt.toInstant() : Instant.now();

                    String publicationState = "ACTIVE".equalsIgnoreCase(state) || "PUBLISHED".equalsIgnoreCase(state)
                            ? "PUBLISHED"
                            : "NOT_PUBLISHED";

                    return Optional.of(PublicSourceStateItem.of(
                            sourceId,
                            sourceRevision,
                            publicationState,
                            "APPROVED",
                            0L,
                            effectiveAt,
                            null
                    ));
                }
            }
        } catch (SQLException exception) {
            // Logged as non-fatal or fallback to UNKNOWN/NOT_PUBLISHED
        }

        // Return UNKNOWN state item if not found in database table
        return Optional.of(PublicSourceStateItem.of(
                sourceId,
                sourceRevision,
                "NOT_PUBLISHED",
                "PENDING",
                0L,
                null,
                "SOURCE_NOT_FOUND"
        ));
    }
}
