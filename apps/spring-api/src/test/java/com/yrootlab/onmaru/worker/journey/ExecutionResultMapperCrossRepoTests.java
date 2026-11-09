package com.yrootlab.onmaru.worker.journey;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yrootlab.onmaru.journey.worker.AiProposalException;
import com.yrootlab.onmaru.journey.worker.CandidatePayload;
import com.yrootlab.onmaru.journey.worker.JourneyCandidate;
import com.yrootlab.onmaru.journey.worker.JourneyWorkerPlan;
import com.yrootlab.onmaru.journey.worker.WorkerDegradedReason;
import com.yrootlab.onmaru.journey.worker.plan.AiJourneyContracts.*;
import com.yrootlab.onmaru.journey.worker.plan.ValidatingExecutionResultMapper;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Runs Spring's mapper against the AI engine's own accepted fixtures pinned at v0.1.2 (see resources README).
 */
class ExecutionResultMapperCrossRepoTests {

    private static final ObjectMapper JSON = new ObjectMapper();
    private final ValidatingExecutionResultMapper mapper = new ValidatingExecutionResultMapper();

    @Test
    void acceptsAiAcceptedProposalAndEvidenceAsOneExecutionResult() throws Exception {
        var fixture = new Pinned();

        JourneyWorkerPlan plan = mapper.map(fixture.binding(), fixture.result(fixture.proposal, fixture.evidence),
                fixture.candidates());

        assertThat(plan.orderedRefs()).containsExactly("place-1");
        assertThat(plan.outcome()).isEqualTo("PROPOSAL");
    }

    @Test
    void rejectsAiFixtureWhenRunIdDoesNotMatchTheApprovedPlan() throws Exception {
        var fixture = new Pinned();
        var otherRun = fixture.proposal;
        ((Map<String, Object>) otherRun).put("runId", UUID.randomUUID().toString());

        assertThatThrownBy(() -> mapper.map(fixture.binding(), fixture.result(otherRun, fixture.evidence),
                fixture.candidates()))
                .isInstanceOfSatisfying(AiProposalException.class,
                        e -> assertThat(e.degradedReason()).isEqualTo(WorkerDegradedReason.AI_INVALID_RESPONSE));
    }

    /** Holds the pinned AI payloads and the approved plan that matches them. */
    private static final class Pinned {
        final Map<String, Object> proposal;
        final Map<String, Object> evidence;
        final Versions versions;
        final UUID runId;
        final String planHash;

        Pinned() throws Exception {
            this.proposal = load("ai-contract/v0.1.2/accepted-validatedproposal.json");
            this.evidence = load("ai-contract/v0.1.2/accepted-evidenceset.json");
            JsonNode v = JSON.valueToTree(proposal.get("versions"));
            this.versions = new Versions(v.get("corpusRevision").asText(), v.get("configHash").asText(),
                    v.get("questionDictionaryVersion").asText(), v.get("sourceRevocationSequence").asLong());
            this.runId = UUID.fromString((String) proposal.get("runId"));
            this.planHash = (String) proposal.get("planHash");
        }

        ApprovedPlanBinding binding() {
            var filters = new Filters("sha256:" + "d".repeat(64), true, List.of("place-1", "place-2"),
                    List.of("SEOUL"), List.of("HANOK"), List.of("place-1"), List.of("place-9"));
            return new ApprovedPlanBinding(new PlanProposal("ai-plan-proposal.v1", "journey-query-plan.v1", runId,
                    Route.SQL_ONLY, RetrievalMode.NONE, List.of(), null, null, List.of(CanonicalField.PUBLIC_ADDRESS),
                    filters, null, versions, new Limits(20, 20, 30, 12, 5), null, planHash));
        }

        ExecutionResult result(Map<String, Object> proposal, Map<String, Object> evidence) {
            return new ExecutionResult("ai-execute-result.v1", runId, planHash, versions, proposal, evidence);
        }

        CandidatePayload candidates() {
            return new CandidatePayload("corpus-1", List.of(new JourneyCandidate("place-1"), new JourneyCandidate("place-2")));
        }

        private static Map<String, Object> load(String path) throws Exception {
            try (InputStream in = ExecutionResultMapperCrossRepoTests.class.getClassLoader().getResourceAsStream(path)) {
                assertThat(in).as(path).isNotNull();
                Map<String, Object> fixture = JSON.readValue(in, new TypeReference<>() { });
                assertThat(fixture.get("expected")).isEqualTo("ACCEPT");
                @SuppressWarnings("unchecked")
                Map<String, Object> payload = (Map<String, Object>) fixture.get("payload");
                return payload;
            }
        }
    }
}
