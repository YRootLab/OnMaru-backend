package com.yrootlab.onmaru.integration.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yrootlab.onmaru.integration.ai.plan.AiPlanWireCodec;
import com.yrootlab.onmaru.integration.ai.plan.DefaultPlanAuthorityGate;
import com.yrootlab.onmaru.journey.worker.AiProposalException;
import com.yrootlab.onmaru.journey.worker.plan.AiJourneyContracts.*;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

class PlanAuthorityGateTests {
    private final ObjectMapper mapper = new ObjectMapper();
    private final AiPlanWireCodec codec = new AiPlanWireCodec();
    private final DefaultPlanAuthorityGate gate = new DefaultPlanAuthorityGate(codec);

    @Test
    void acceptsPinnedPythonFixturesAndComputesIdenticalUnicodePlanHash() throws Exception {
        for (String name : List.of("sql_only", "hybrid")) {
            var fixture = fixture(name);
            var plan = codec.readPlan(mapper.writeValueAsBytes(fixture.get("payload")));
            var request = codec.readSafeQuery(mapper.writeValueAsBytes(fixture.path("context").get("request")));
            var binding = gate.approve(request, plan, request.versions());
            assertThat(binding.plan()).isEqualTo(plan);
            assertThat(codec.planHash(plan)).isEqualTo(plan.planHash());
            assertThat(mapper.readTree(codec.write(request))).isEqualTo(fixture.path("context").get("request"));
        }
    }

    @Test
    void rejectsHashTamperingVersionDriftAndFilterExpansion() throws Exception {
        var fixture = fixture("sql_only");
        var request = codec.readSafeQuery(mapper.writeValueAsBytes(fixture.path("context").get("request")));
        var original = (ObjectNode) fixture.get("payload");
        var tampered = original.deepCopy();
        tampered.put("planHash", "sha256:" + "b".repeat(64));
        assertThatThrownBy(() -> gate.approve(request, codec.readPlan(mapper.writeValueAsBytes(tampered)), request.versions()))
                .isInstanceOf(AiProposalException.class);
        var drift = new Versions("corpus-2", request.versions().configHash(), "questions-1", 7);
        var plan = codec.readPlan(mapper.writeValueAsBytes(original));
        assertThatThrownBy(() -> gate.approve(request, plan, drift)).isInstanceOf(AiProposalException.class);
        var expanded = original.deepCopy();
        ((ObjectNode) expanded.get("proposedFilters")).putNull("regionCodes");
        var expandedPlan = codec.readPlan(mapper.writeValueAsBytes(expanded));
        assertThatThrownBy(() -> gate.approve(request, expandedPlan, request.versions())).isInstanceOf(AiProposalException.class);
    }

    @Test
    void rejectsUnknownMissingDuplicateAndCoercedFields() throws Exception {
        var body = (ObjectNode) fixture("sql_only").get("payload");
        var extra = body.deepCopy().put("rawQuestion", "private");
        assertThatThrownBy(() -> codec.readPlan(mapper.writeValueAsBytes(extra))).isInstanceOf(AiProposalException.class);
        var missing = body.deepCopy(); missing.remove("semanticQuery");
        assertThatThrownBy(() -> codec.readPlan(mapper.writeValueAsBytes(missing))).isInstanceOf(AiProposalException.class);
        var coerced = body.deepCopy(); ((ObjectNode)coerced.get("proposedFilters")).put("publicOnly", "true");
        assertThatThrownBy(() -> codec.readPlan(mapper.writeValueAsBytes(coerced))).isInstanceOf(AiProposalException.class);
        assertThatThrownBy(() -> codec.readPlan("{\"runId\":\"a\",\"runId\":\"b\"}".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(AiProposalException.class);
    }

    private com.fasterxml.jackson.databind.JsonNode fixture(String route) throws Exception {
        try (var stream = getClass().getResourceAsStream("/ai-plan-execute/accepted-plan-" + route + ".json")) {
            return mapper.readTree(stream);
        }
    }
}
