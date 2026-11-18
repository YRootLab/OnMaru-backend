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

        var pinnedExpansion = original.deepCopy();
        ((com.fasterxml.jackson.databind.node.ArrayNode) pinnedExpansion.path("proposedFilters").path("pinnedPlaceIds"))
                .add("place-2");
        var unhashedPinnedExpansion = codec.readPlan(mapper.writeValueAsBytes(pinnedExpansion));
        pinnedExpansion.put("planHash", codec.planHash(unhashedPinnedExpansion));
        var pinnedExpansionPlan = codec.readPlan(mapper.writeValueAsBytes(pinnedExpansion));
        assertThatThrownBy(() -> gate.approve(request, pinnedExpansionPlan, request.versions()))
                .isInstanceOf(AiProposalException.class);
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

    @Test
    void rejectsSchemaInvalidIdentifiersDuplicatesOversizedFiltersAndQueryGrant() throws Exception {
        var fixture = fixture("sql_only");
        var request = codec.readSafeQuery(mapper.writeValueAsBytes(fixture.path("context").get("request")));

        var duplicate = (ObjectNode) fixture.get("payload").deepCopy();
        ((com.fasterxml.jackson.databind.node.ArrayNode) duplicate.path("proposedFilters").path("excludedPlaceIds"))
                .add("place-9");
        assertThatThrownBy(() -> gate.approve(request, rehashed(duplicate), request.versions()))
                .isInstanceOf(AiProposalException.class);

        var oversized = (ObjectNode) fixture.get("payload").deepCopy();
        var excluded = (com.fasterxml.jackson.databind.node.ArrayNode) oversized.path("proposedFilters").path("excludedPlaceIds");
        excluded.removeAll();
        for (int index = 0; index <= 1_000; index++) excluded.add("place-" + index);
        assertThatThrownBy(() -> gate.approve(request, rehashed(oversized), request.versions()))
                .isInstanceOf(AiProposalException.class);

        var malformed = (ObjectNode) fixture.get("payload").deepCopy();
        ((com.fasterxml.jackson.databind.node.ArrayNode) malformed.path("proposedFilters").path("excludedPlaceIds"))
                .add("contains space");
        assertThatThrownBy(() -> gate.approve(request, rehashed(malformed), request.versions()))
                .isInstanceOf(AiProposalException.class);

        var plan = codec.readPlan(mapper.writeValueAsBytes(fixture.get("payload")));
        assertThatThrownBy(() -> new ApprovedPlanBinding(plan).forExecution("contains space"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void approvesPinnedRecommendationWithUnboundedNullPlaceIdsAndAllFiveRoutes() throws Exception {
        var fixture = fixture("hybrid");
        var baseRequest = (ObjectNode) fixture.path("context").get("request").deepCopy();
        ((ObjectNode) baseRequest.path("authorityBounds").path("filters")).putNull("placeIds");
        var request = codec.readSafeQuery(mapper.writeValueAsBytes(baseRequest));

        var basePlan = (ObjectNode) fixture.get("payload").deepCopy();
        ((ObjectNode) basePlan.path("proposedFilters")).putNull("placeIds");
        ((ObjectNode) basePlan.path("questionFrame")).put("operation", "RECOMMEND");
        ((ObjectNode) basePlan.path("questionFrame")).withArray("targetPlaceIds").removeAll();
        ((ObjectNode) basePlan.path("retrievalExpression")).withArray("publicEntityIds").removeAll();

        record RouteSpec(String route, String retrievalMode, String reasonCode, List<String> fields, String semanticQuery, ObjectNode clarification) {}
        var specs = List.of(
                new RouteSpec("SQL_ONLY", "NONE", "CANONICAL_FACT_ONLY", List.of("PUBLIC_ADDRESS"), null, null),
                new RouteSpec("LEXICAL_ONLY", "LEXICAL_ONLY", "EXACT_PUBLIC_TERM", List.of(), "quiet hanok", null),
                new RouteSpec("DENSE_ONLY", "DENSE_ONLY", "SINGLE_SEMANTIC_TOPIC", List.of(), "quiet hanok", null),
                new RouteSpec("HYBRID", "HYBRID", "COMPOSITE_QUERY", List.of(), "quiet hanok", null),
                new RouteSpec("SQL_PLUS_RETRIEVAL", "HYBRID", "CANONICAL_FACT_WITH_RETRIEVAL", List.of("PUBLIC_ADDRESS"), "quiet hanok", null),
                new RouteSpec("CLARIFY_OR_SCOPE", "NONE", "AMBIGUOUS_TARGET", List.of(), null,
                        mapper.createObjectNode().put("reason", "AMBIGUOUS_TARGET").put("questionCode", "CHOOSE_TARGET"))
        );

        for (var spec : specs) {
            var candidate = basePlan.deepCopy();
            candidate.put("route", spec.route());
            candidate.put("retrievalMode", spec.retrievalMode());
            var reasons = candidate.putArray("reasonCodes");
            reasons.add(spec.reasonCode());
            var fields = candidate.putArray("requestedCanonicalFields");
            spec.fields().forEach(fields::add);
            if (spec.semanticQuery() == null) {
                candidate.putNull("semanticQuery");
            } else {
                candidate.put("semanticQuery", spec.semanticQuery());
            }
            if (spec.clarification() == null) {
                candidate.putNull("clarification");
            } else {
                candidate.set("clarification", spec.clarification());
            }
            PlanProposal approved = rehashed(candidate);
            var binding = gate.approve(request, approved, request.versions());
            assertThat(binding.plan().proposedFilters().placeIds()).isNull();
            assertThat(binding.plan().proposedFilters().pinnedPlaceIds()).containsExactly("place-1");
        }
    }

    private PlanProposal rehashed(ObjectNode document) throws Exception {
        var withoutUpdatedHash = codec.readPlan(mapper.writeValueAsBytes(document));
        document.put("planHash", codec.planHash(withoutUpdatedHash));
        return codec.readPlan(mapper.writeValueAsBytes(document));
    }

    private com.fasterxml.jackson.databind.JsonNode fixture(String route) throws Exception {
        try (var stream = getClass().getResourceAsStream("/ai-plan-execute/accepted-plan-" + route + ".json")) {
            return mapper.readTree(stream);
        }
    }
}
