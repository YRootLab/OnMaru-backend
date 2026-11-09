package com.yrootlab.onmaru.journey.worker.plan;

import com.yrootlab.onmaru.journey.worker.AiProposalException;
import com.yrootlab.onmaru.journey.worker.CandidatePayload;
import com.yrootlab.onmaru.journey.worker.JourneyCandidate;
import com.yrootlab.onmaru.journey.worker.JourneyWorkerPlan;
import com.yrootlab.onmaru.journey.worker.WorkerDegradedReason;
import com.yrootlab.onmaru.journey.worker.plan.AiJourneyContracts.*;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ValidatingExecutionResultMapperTests {

    private static final String PLAN_HASH = "sha256:" + "a".repeat(64);
    private final UUID runId = UUID.randomUUID();
    private final Versions versions = new Versions("corpus-1", "sha256:" + "c".repeat(64), "dict-1", 7);
    private final ValidatingExecutionResultMapper mapper = new ValidatingExecutionResultMapper();
    private final CandidatePayload candidates = new CandidatePayload("rev-1", List.of(
            new JourneyCandidate("place-1"), new JourneyCandidate("place-2"), new JourneyCandidate("place-3")));

    @Test
    void mapsGeneratedProposalToOrderedApprovedBoard() {
        JourneyWorkerPlan plan = mapper.map(binding(filters(null, List.of())), result(proposal(
                List.of("place-1", "place-2"), "GENERATED", List.of(place("place-1", "c1", "e1"), place("place-2", "c2", "e2"))),
                evidence(List.of("place-1", "place-2"), List.of(ev("e1", "ch1", "place-1"), ev("e2", "ch2", "place-2")))),
                candidates);

        assertThat(plan.orderedRefs()).containsExactly("place-1", "place-2");
        assertThat(plan.outcome()).isEqualTo("PROPOSAL");
    }

    @Test
    void notRunWithoutPlacesIsNoResults() {
        JourneyWorkerPlan plan = mapper.map(binding(filters(null, List.of())), result(
                proposal(List.of(), "NOT_RUN", List.of()), evidence(List.of(), List.of())), candidates);

        assertThat(plan.orderedRefs()).isEmpty();
        assertThat(plan.outcome()).isEqualTo("NO_RESULTS");
    }

    @Test
    void rejectsRunPlanAndVersionMismatches() {
        var valid = binding(filters(null, List.of()));
        var good = result(proposal(List.of(), "NOT_RUN", List.of()), evidence(List.of(), List.of()));

        assertRejected(valid, withRunId(good, UUID.randomUUID()));
        assertRejected(valid, withPlanHash(good, "sha256:" + "b".repeat(64)));
        assertRejected(valid, withVersions(good, new Versions("corpus-2", versions.configHash(),
                versions.questionDictionaryVersion(), versions.sourceRevocationSequence())));
    }

    @Test
    void rejectsEnvelopeRunOrVersionMismatchInsideProposalAndEvidence() {
        var valid = binding(filters(null, List.of()));
        Map<String, Object> badProposal = proposal(List.of(), "NOT_RUN", List.of());
        badProposal.put("runId", UUID.randomUUID().toString());
        assertRejected(valid, result(badProposal, evidence(List.of(), List.of())));

        Map<String, Object> badEvidence = evidence(List.of(), List.of());
        badEvidence.put("versions", versionsMap("corpus-9"));
        assertRejected(valid, result(proposal(List.of(), "NOT_RUN", List.of()), badEvidence));
    }

    @Test
    void rejectsPlacesOutsideCandidatesFilterOrExcludedSet() {
        var notCandidate = result(proposal(List.of("place-9"), "GENERATED", List.of(place("place-9", "c1", "e1"))),
                evidence(List.of("place-9"), List.of(ev("e1", "ch1", "place-9"))));
        assertRejected(binding(filters(null, List.of())), notCandidate);

        var outsideFilter = result(proposal(List.of("place-2"), "GENERATED", List.of(place("place-2", "c1", "e1"))),
                evidence(List.of("place-2"), List.of(ev("e1", "ch1", "place-2"))));
        assertRejected(binding(filters(List.of("place-1"), List.of())), outsideFilter);
        assertRejected(binding(filters(null, List.of("place-2"))), outsideFilter);
    }

    @Test
    void rejectsClaimCitingEvidenceOfAnotherPlace() {
        var crossed = result(proposal(List.of("place-1"), "GENERATED", List.of(place("place-1", "c1", "e2"))),
                evidence(List.of("place-1", "place-2"), List.of(ev("e1", "ch1", "place-1"), ev("e2", "ch2", "place-2"))));
        assertRejected(binding(filters(null, List.of())), crossed);
    }

    @Test
    void rejectsReorderedCandidatesAndDuplicatePlaces() {
        var reordered = proposal(List.of("place-1", "place-2"), "GENERATED",
                List.of(place("place-2", "c1", "e1"), place("place-1", "c2", "e2")));
        reordered.put("candidatePlaceIds", List.of("place-1", "place-2"));
        assertRejected(binding(filters(null, List.of())), result(reordered,
                evidence(List.of("place-1", "place-2"), List.of(ev("e1", "ch1", "place-2"), ev("e2", "ch2", "place-1")))));

        var duplicate = proposal(List.of("place-1", "place-1"), "GENERATED",
                List.of(place("place-1", "c1", "e1"), place("place-1", "c2", "e1")));
        assertRejected(binding(filters(null, List.of())), result(duplicate,
                evidence(List.of("place-1"), List.of(ev("e1", "ch1", "place-1")))));
    }

    @Test
    void rejectsFailedGenerationDuplicateClaimsAndUnknownEvidence() {
        assertRejected(binding(filters(null, List.of())), result(
                proposal(List.of(), "FAILED", List.of()), evidence(List.of(), List.of())));

        var duplicateClaims = result(proposal(List.of("place-1", "place-2"), "GENERATED",
                List.of(place("place-1", "c1", "e1"), place("place-2", "c1", "e2"))),
                evidence(List.of("place-1", "place-2"), List.of(ev("e1", "ch1", "place-1"), ev("e2", "ch2", "place-2"))));
        assertRejected(binding(filters(null, List.of())), duplicateClaims);

        var unknownEvidence = result(proposal(List.of("place-1"), "GENERATED", List.of(place("place-1", "c1", "missing"))),
                evidence(List.of("place-1"), List.of(ev("e1", "ch1", "place-1"))));
        assertRejected(binding(filters(null, List.of())), unknownEvidence);
    }

    @Test
    void rejectsEvidencePlacesNotMatchingAllowedSet() {
        var mismatched = result(proposal(List.of(), "NOT_RUN", List.of()),
                evidence(List.of("place-1"), List.of(ev("e1", "ch1", "place-2"))));
        assertRejected(binding(filters(null, List.of())), mismatched);
    }

    private static void assertRejected(ApprovedPlanBinding binding, ExecutionResult result) {
        assertThatThrownBy(() -> new ValidatingExecutionResultMapper().map(binding, result, new CandidatePayload(
                "rev-1", List.of(new JourneyCandidate("place-1"), new JourneyCandidate("place-2"), new JourneyCandidate("place-3")))))
                .isInstanceOfSatisfying(AiProposalException.class,
                        e -> assertThat(e.degradedReason()).isEqualTo(WorkerDegradedReason.AI_INVALID_RESPONSE));
    }

    private ApprovedPlanBinding binding(Filters filters) {
        return new ApprovedPlanBinding(new PlanProposal("ai-plan-proposal.v1", "journey-query-plan.v1", runId,
                Route.HYBRID, RetrievalMode.HYBRID, List.of(), null, null, List.of(CanonicalField.PUBLIC_ADDRESS),
                filters, null, versions, new Limits(10, 10, 5, 5, 5), null, PLAN_HASH));
    }

    private Filters filters(List<String> placeIds, List<String> excluded) {
        return new Filters("sha256:" + "d".repeat(64), true, placeIds, null, null, List.of(), excluded);
    }

    private ExecutionResult result(Map<String, Object> proposal, Map<String, Object> evidence) {
        return new ExecutionResult("ai-execute-result.v1", runId, PLAN_HASH, versions, proposal, evidence);
    }

    private ExecutionResult withRunId(ExecutionResult r, UUID id) {
        return new ExecutionResult(r.schemaVersion(), id, r.planHash(), r.versions(), r.proposal(), r.evidence());
    }

    private ExecutionResult withPlanHash(ExecutionResult r, String hash) {
        return new ExecutionResult(r.schemaVersion(), r.runId(), hash, r.versions(), r.proposal(), r.evidence());
    }

    private ExecutionResult withVersions(ExecutionResult r, Versions v) {
        return new ExecutionResult(r.schemaVersion(), r.runId(), r.planHash(), v, r.proposal(), r.evidence());
    }

    private Map<String, Object> proposal(List<String> candidateIds, String generation, List<Map<String, Object>> places) {
        Map<String, Object> map = new HashMap<>();
        map.put("schemaVersion", "ai-validated-proposal.v1");
        map.put("runId", runId.toString());
        map.put("planHash", PLAN_HASH);
        map.put("versions", versionsMap(versions.corpusRevision()));
        map.put("candidatePlaceIds", new ArrayList<>(candidateIds));
        map.put("places", new ArrayList<>(places));
        map.put("summaryClaims", List.of());
        map.put("retrievalStatus", "COMPLETE");
        map.put("generationStatus", generation);
        map.put("failureStage", null);
        return map;
    }

    private Map<String, Object> evidence(List<String> allowed, List<Map<String, Object>> items) {
        Map<String, Object> map = new HashMap<>();
        map.put("schemaVersion", "ai-evidence-set.v1");
        map.put("runId", runId.toString());
        map.put("planHash", PLAN_HASH);
        map.put("versions", versionsMap(versions.corpusRevision()));
        map.put("allowedPlaceIds", new ArrayList<>(allowed));
        map.put("evidence", new ArrayList<>(items));
        return map;
    }

    private Map<String, Object> versionsMap(String corpus) {
        return Map.of("corpusRevision", corpus, "configHash", versions.configHash(),
                "questionDictionaryVersion", versions.questionDictionaryVersion(),
                "sourceRevocationSequence", versions.sourceRevocationSequence());
    }

    private static Map<String, Object> place(String placeId, String claimId, String evidenceId) {
        return Map.of("placeId", placeId, "reasonClaims", List.of(Map.of(
                "claimId", claimId, "text", "근거 문장", "kind", "POSITIVE", "evidenceIds", List.of(evidenceId))));
    }

    private static Map<String, Object> ev(String evidenceId, String chunkId, String placeId) {
        return Map.of("evidenceId", evidenceId, "chunkId", chunkId, "placeId", placeId,
                "sourceId", "src-1", "sourceRevision", "rev-1", "startOffset", 0, "endOffset", 10);
    }
}
