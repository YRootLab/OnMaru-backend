package com.yrootlab.onmaru.persistence.journey.exploration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yrootlab.onmaru.journey.worker.plan.JourneyPlanExecutionPorts.ExplorationContextReader;
import com.yrootlab.onmaru.journey.worker.plan.JourneyPlanExecutionPorts.ExplorationSafeContext;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Reads persisted pinned/excluded/last-confirmed place refs for safe-query construction.
 */
public final class JdbcExplorationContextReader implements ExplorationContextReader {

    private static final String SQL = """
            SELECT pinned_refs::text AS pinned_json,
                   excluded_refs::text AS excluded_json,
                   board::text AS board_json
            FROM onmaru.discovery_explorations
            WHERE id = ? AND deleted_at IS NULL
            """;

    private final DataSource dataSource;
    private final ObjectMapper objectMapper;

    public JdbcExplorationContextReader(DataSource dataSource, ObjectMapper objectMapper) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource must not be null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
    }

    @Override
    public ExplorationSafeContext read(UUID explorationId) {
        if (explorationId == null) {
            return ExplorationSafeContext.empty();
        }
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement(SQL)) {
            statement.setObject(1, explorationId);
            try (var rs = statement.executeQuery()) {
                if (!rs.next()) {
                    return ExplorationSafeContext.empty();
                }
                List<String> pinned = readRefIds(rs.getString("pinned_json"));
                List<String> excluded = readRefIds(rs.getString("excluded_json"));
                List<String> lastConfirmed = readOrderedRefs(rs.getString("board_json"));
                return new ExplorationSafeContext(pinned, excluded, lastConfirmed);
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("failed to read exploration context", exception);
        }
    }

    private List<String> readRefIds(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            JsonNode array = objectMapper.readTree(json);
            if (!array.isArray()) {
                return List.of();
            }
            List<String> ids = new ArrayList<>();
            for (JsonNode item : array) {
                if (item.isObject()) {
                    String type = item.path("type").asText("");
                    String id = item.path("id").asText("");
                    if (!id.isBlank() && (type.isBlank() || "PLACE".equals(type))) {
                        ids.add(id);
                    }
                } else if (item.isTextual() && !item.asText().isBlank()) {
                    ids.add(item.asText());
                }
            }
            return List.copyOf(ids);
        } catch (Exception ignored) {
            return List.of();
        }
    }

    private List<String> readOrderedRefs(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            JsonNode root = objectMapper.readTree(json);
            JsonNode refs = root.path("orderedRefs");
            if (!refs.isArray()) {
                return List.of();
            }
            List<String> ids = new ArrayList<>();
            for (JsonNode ref : refs) {
                if (ref.isTextual() && !ref.asText().isBlank()) {
                    ids.add(ref.asText());
                }
            }
            return List.copyOf(ids);
        } catch (Exception ignored) {
            return List.of();
        }
    }
}
