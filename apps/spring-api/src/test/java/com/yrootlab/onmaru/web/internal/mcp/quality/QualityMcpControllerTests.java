package com.yrootlab.onmaru.web.internal.mcp.quality;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yrootlab.onmaru.catalog.application.query.quality.PublicSourceStateItem;
import com.yrootlab.onmaru.catalog.application.query.quality.PublicSourceStateQueryPort;
import com.yrootlab.onmaru.journey.observation.ObservationOutcome;
import com.yrootlab.onmaru.journey.observation.ObservationRoute;
import com.yrootlab.onmaru.journey.observation.ObservationStatus;
import com.yrootlab.onmaru.journey.observation.RunObservation;
import com.yrootlab.onmaru.journey.observation.RunObservationQueryPort;
import com.yrootlab.onmaru.journey.observation.StageExecutionStatus;
import com.yrootlab.onmaru.journey.observation.StageObservation;
import com.yrootlab.onmaru.journey.observation.TraceCoverage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class QualityMcpControllerTests {

    private MockMvc mockMvc;
    private ObjectMapper objectMapper;
    private UUID knownRunId;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        knownRunId = UUID.randomUUID();

        RunObservation observation = RunObservation.builder(knownRunId)
                .traceId("4bf92f3577b34da6a3ce929d0e0e4736")
                .observationStatus(ObservationStatus.COMPLETE)
                .traceCoverage(TraceCoverage.AVAILABLE)
                .route(ObservationRoute.HYBRID)
                .outcome(ObservationOutcome.SUCCESS)
                .corpusRevision("corpus-20261105")
                .configHash("cfg-hash-1")
                .promptVersion("v1")
                .stages(List.of(
                        StageObservation.of(
                                "RETRIEVAL",
                                StageExecutionStatus.SUCCESS,
                                null,
                                null,
                                120,
                                10,
                                5
                        )
                ))
                .build();

        RunObservationQueryPort runPort = runId -> runId.equals(knownRunId)
                ? Optional.of(observation)
                : Optional.empty();

        PublicSourceStateQueryPort sourcePort = (sourceId, sourceRevision) ->
                "tour-api-spot".equals(sourceId) && "rev-20261105".equals(sourceRevision)
                        ? Optional.of(PublicSourceStateItem.of(
                        sourceId,
                        sourceRevision,
                        "PUBLISHED",
                        "APPROVED",
                        0L,
                        Instant.parse("2026-11-05T00:00:00Z"),
                        null
                ))
                        : Optional.empty();

        var controller = new QualityMcpController(runPort, sourcePort);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    @DisplayName("quality.read.run scope가 있으면 quality_get_run_observation 조회가 성공한다")
    void getsRunObservationWithValidScope() throws Exception {
        var request = new QualityRunObservationRequest(knownRunId.toString());

        mockMvc.perform(post("/internal/mcp/quality/tools/quality_get_run_observation")
                        .header("X-Scope", "quality.read.run")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.schemaVersion").value("run-observation.v1"))
                .andExpect(jsonPath("$.runId").value(knownRunId.toString()))
                .andExpect(jsonPath("$.traceId").value("4bf92f3577b34da6a3ce929d0e0e4736"))
                .andExpect(jsonPath("$.observationStatus").value("COMPLETE"))
                .andExpect(jsonPath("$.route").value("HYBRID"))
                .andExpect(jsonPath("$.outcome").value("SUCCESS"))
                .andExpect(jsonPath("$.stages[0].stage").value("RETRIEVAL"))
                .andExpect(jsonPath("$.stages[0].durationMs").value(120));
    }

    @Test
    @DisplayName("quality.read.run scope가 없거나 online scope인 경우 403 SCOPE_DENIED 반환")
    void rejectsRunObservationWithoutScope() throws Exception {
        var request = new QualityRunObservationRequest(knownRunId.toString());

        mockMvc.perform(post("/internal/mcp/quality/tools/quality_get_run_observation")
                        .header("X-Scope", "journey.proposal:write")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("SCOPE_DENIED"));
    }

    @Test
    @DisplayName("존재하지 않는 runId 조회 시 404 NOT_FOUND_OR_HIDDEN 반환")
    void returns404ForUnknownRunId() throws Exception {
        var request = new QualityRunObservationRequest(UUID.randomUUID().toString());

        mockMvc.perform(post("/internal/mcp/quality/tools/quality_get_run_observation")
                        .header("X-Scope", "quality.read.run")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND_OR_HIDDEN"));
    }

    @Test
    @DisplayName("quality.read.source scope가 있으면 quality_get_public_source_state 조회가 성공한다")
    void getsPublicSourceStateWithValidScope() throws Exception {
        var request = new QualityPublicSourceStateRequest("tour-api-spot", "rev-20261105");

        mockMvc.perform(post("/internal/mcp/quality/tools/quality_get_public_source_state")
                        .header("X-Scope", "quality.read.source")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sourceId").value("tour-api-spot"))
                .andExpect(jsonPath("$.sourceRevision").value("rev-20261105"))
                .andExpect(jsonPath("$.publicationState").value("PUBLISHED"))
                .andExpect(jsonPath("$.licenseReviewState").value("APPROVED"))
                .andExpect(jsonPath("$.revocationSequence").value(0));
    }

    @Test
    @DisplayName("quality.read.source scope가 없으면 403 SCOPE_DENIED 반환")
    void rejectsSourceStateWithoutScope() throws Exception {
        var request = new QualityPublicSourceStateRequest("tour-api-spot", "rev-20261105");

        mockMvc.perform(post("/internal/mcp/quality/tools/quality_get_public_source_state")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("SCOPE_DENIED"));
    }
}
