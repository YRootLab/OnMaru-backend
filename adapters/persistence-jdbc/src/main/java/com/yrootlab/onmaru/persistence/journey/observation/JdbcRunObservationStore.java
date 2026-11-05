package com.yrootlab.onmaru.persistence.journey.observation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yrootlab.onmaru.journey.observation.ObservationOutcome;
import com.yrootlab.onmaru.journey.observation.ObservationRoute;
import com.yrootlab.onmaru.journey.observation.ObservationStatus;
import com.yrootlab.onmaru.journey.observation.RunObservation;
import com.yrootlab.onmaru.journey.observation.RunObservationStore;
import com.yrootlab.onmaru.journey.observation.StageExecutionStatus;
import com.yrootlab.onmaru.journey.observation.StageObservation;
import com.yrootlab.onmaru.journey.observation.TraceCoverage;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public final class JdbcRunObservationStore implements RunObservationStore {

    private final DataSource dataSource;
    private final ObjectMapper objectMapper;

    public JdbcRunObservationStore(DataSource dataSource, ObjectMapper objectMapper) {
        this.dataSource = dataSource;
        this.objectMapper = objectMapper;
    }

    public JdbcRunObservationStore(DataSource dataSource) {
        this(dataSource, new ObjectMapper());
    }

    @Override
    public void save(RunObservation observation) {
        if (observation == null) {
            return;
        }

        String stagesJson;
        try {
            stagesJson = serializeStages(observation.stages());
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Failed to serialize stages observation", e);
        }

        try (Connection connection = dataSource.getConnection();
             var statement = connection.prepareStatement("""
                     INSERT INTO onmaru.discovery_run_observations (
                         run_id,
                         trace_id,
                         observation_status,
                         trace_coverage,
                         route,
                         outcome,
                         corpus_revision,
                         config_hash,
                         prompt_version,
                         stages,
                         updated_at
                     ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, CURRENT_TIMESTAMP)
                     ON CONFLICT (run_id) DO UPDATE SET
                         trace_id = EXCLUDED.trace_id,
                         observation_status = EXCLUDED.observation_status,
                         trace_coverage = EXCLUDED.trace_coverage,
                         route = EXCLUDED.route,
                         outcome = EXCLUDED.outcome,
                         corpus_revision = EXCLUDED.corpus_revision,
                         config_hash = EXCLUDED.config_hash,
                         prompt_version = EXCLUDED.prompt_version,
                         stages = EXCLUDED.stages,
                         updated_at = CURRENT_TIMESTAMP
                     """)) {

            statement.setObject(1, observation.runId());
            statement.setString(2, observation.traceId());
            statement.setString(3, observation.observationStatus().name());
            statement.setString(4, observation.traceCoverage().name());
            statement.setString(5, observation.route().name());
            statement.setString(6, observation.outcome().name());
            statement.setString(7, observation.corpusRevision());
            statement.setString(8, observation.configHash());
            statement.setString(9, observation.promptVersion());
            statement.setString(10, stagesJson);

            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to persist run observation for runId " + observation.runId(), e);
        }
    }

    @Override
    public Optional<RunObservation> findByRunId(UUID runId) {
        if (runId == null) {
            return Optional.empty();
        }

        try (Connection connection = dataSource.getConnection();
             var statement = connection.prepareStatement("""
                     SELECT run_id, trace_id, observation_status, trace_coverage, route, outcome,
                            corpus_revision, config_hash, prompt_version, stages
                     FROM onmaru.discovery_run_observations
                     WHERE run_id = ?
                     """)) {

            statement.setObject(1, runId);

            try (ResultSet resultSet = statement.executeQuery()) {
                if (resultSet.next()) {
                    return Optional.of(mapRow(resultSet));
                }
                return Optional.empty();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to query run observation for runId " + runId, e);
        }
    }

    private RunObservation mapRow(ResultSet resultSet) throws SQLException {
        UUID runId = resultSet.getObject("run_id", UUID.class);
        String traceId = resultSet.getString("trace_id");
        ObservationStatus observationStatus = ObservationStatus.fromString(resultSet.getString("observation_status"));
        TraceCoverage traceCoverage = TraceCoverage.fromString(resultSet.getString("trace_coverage"));
        ObservationRoute route = ObservationRoute.fromString(resultSet.getString("route"));
        ObservationOutcome outcome = ObservationOutcome.fromString(resultSet.getString("outcome"));
        String corpusRevision = resultSet.getString("corpus_revision");
        String configHash = resultSet.getString("config_hash");
        String promptVersion = resultSet.getString("prompt_version");
        String stagesJson = resultSet.getString("stages");

        List<StageObservation> stages = deserializeStages(stagesJson);

        return new RunObservation(
                RunObservation.SCHEMA_VERSION,
                runId,
                traceId,
                observationStatus,
                traceCoverage,
                route,
                outcome,
                corpusRevision,
                configHash,
                promptVersion,
                stages
        );
    }

    private String serializeStages(List<StageObservation> stages) throws JsonProcessingException {
        if (stages == null || stages.isEmpty()) {
            return "[]";
        }
        var rawList = new ArrayList<Map<String, Object>>(stages.size());
        for (var stage : stages) {
            var map = new java.util.LinkedHashMap<String, Object>();
            map.put("stage", stage.stage());
            map.put("status", stage.status().name());
            if (stage.errorCode() != null) map.put("errorCode", stage.errorCode());
            if (stage.reasonCode() != null) map.put("reasonCode", stage.reasonCode());
            if (stage.durationMs() != null) map.put("durationMs", stage.durationMs());
            if (stage.candidateCount() != null) map.put("candidateCount", stage.candidateCount());
            if (stage.evidenceCount() != null) map.put("evidenceCount", stage.evidenceCount());
            rawList.add(map);
        }
        return objectMapper.writeValueAsString(rawList);
    }

    private List<StageObservation> deserializeStages(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            List<Map<String, Object>> list = objectMapper.readValue(json, new TypeReference<>() {});
            var result = new ArrayList<StageObservation>(list.size());
            for (var map : list) {
                String stage = (String) map.get("stage");
                String statusStr = (String) map.get("status");
                StageExecutionStatus status = StageExecutionStatus.fromString(statusStr);
                String errorCode = (String) map.get("errorCode");
                String reasonCode = (String) map.get("reasonCode");
                Integer durationMs = map.get("durationMs") instanceof Number num ? num.intValue() : null;
                Integer candidateCount = map.get("candidateCount") instanceof Number num ? num.intValue() : null;
                Integer evidenceCount = map.get("evidenceCount") instanceof Number num ? num.intValue() : null;

                result.add(new StageObservation(stage, status, errorCode, reasonCode, durationMs, candidateCount, evidenceCount));
            }
            return List.copyOf(result);
        } catch (JsonProcessingException e) {
            return List.of();
        }
    }
}
