package com.yrootlab.onmaru.journey.worker.plan;

import com.yrootlab.onmaru.journey.worker.AiProposalException;
import com.yrootlab.onmaru.journey.worker.CandidatePayload;
import com.yrootlab.onmaru.journey.worker.JourneyCandidate;
import com.yrootlab.onmaru.journey.worker.JourneyWorkerPlan;
import com.yrootlab.onmaru.journey.worker.WorkerDegradedReason;
import com.yrootlab.onmaru.journey.worker.plan.AiJourneyContracts.ApprovedPlanBinding;
import com.yrootlab.onmaru.journey.worker.plan.AiJourneyContracts.ExecutionResult;
import com.yrootlab.onmaru.journey.worker.plan.AiJourneyContracts.Filters;
import com.yrootlab.onmaru.journey.worker.plan.AiJourneyContracts.PlanProposal;
import com.yrootlab.onmaru.journey.worker.plan.AiJourneyContracts.Versions;
import com.yrootlab.onmaru.journey.worker.plan.JourneyPlanExecutionPorts.ExecutionResultMapper;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Re-validates an untrusted AI ExecutionResult against the approved plan, the evidence it cites, and Spring's
 * own candidate set. Any mismatch is an invalid AI response, so the worker falls back to the baseline board.
 */
public final class ValidatingExecutionResultMapper implements ExecutionResultMapper {

    static final String RESULT_SCHEMA = "ai-execute-result.v1";
    static final String PROPOSAL_SCHEMA = "ai-validated-proposal.v1";
    static final String EVIDENCE_SCHEMA = "ai-evidence-set.v1";
    static final int MAX_PLACES = 5;
    static final int MAX_EVIDENCE = 12;
    static final int MAX_CLAIMS = 12;
    static final int MAX_CLAIM_EVIDENCE = 3;

    @Override
    public JourneyWorkerPlan map(ApprovedPlanBinding binding, ExecutionResult result, CandidatePayload candidates) {
        if (binding == null || binding.plan() == null || result == null || candidates == null) {
            throw invalid();
        }
        PlanProposal plan = binding.plan();
        require(RESULT_SCHEMA.equals(result.schemaVersion()));
        require(plan.runId().equals(result.runId()));
        require(plan.planHash().equals(result.planHash()));
        require(plan.versions().equals(result.versions()));

        Map<String, Object> evidenceEnvelope = object(result.evidence());
        requireEnvelope(evidenceEnvelope, EVIDENCE_SCHEMA, plan);
        EvidenceIndex evidence = EvidenceIndex.from(evidenceEnvelope);

        Map<String, Object> proposal = object(result.proposal());
        requireEnvelope(proposal, PROPOSAL_SCHEMA, plan);
        String generation = text(proposal, "generationStatus");
        if (!"GENERATED".equals(generation) && !"NOT_RUN".equals(generation)) {
            throw invalid();
        }

        List<Map<String, Object>> places = objects(proposal.get("places"));
        if (places.size() > MAX_PLACES) {
            throw invalid();
        }
        List<String> placeIds = new ArrayList<>();
        for (Map<String, Object> place : places) {
            placeIds.add(text(place, "placeId"));
        }
        if (placeIds.size() != new HashSet<>(placeIds).size()) {
            throw invalid();
        }
        // The AI must preserve the candidate order it was given; a reorder is treated as a contract violation.
        if (!placeIds.equals(strings(list(proposal.get("candidatePlaceIds"))))) {
            throw invalid();
        }
        if (!placeIds.isEmpty() && !"GENERATED".equals(generation)) {
            throw invalid();
        }

        Set<String> candidateRefs = new HashSet<>();
        for (JourneyCandidate candidate : candidates.candidates()) {
            candidateRefs.add(candidate.ref());
        }
        Filters filters = plan.proposedFilters();
        Set<String> filterAllowed = filters == null || filters.placeIds() == null ? null : new HashSet<>(filters.placeIds());
        Set<String> excluded = filters == null || filters.excludedPlaceIds() == null
                ? Set.of() : new HashSet<>(filters.excludedPlaceIds());

        Set<String> claimIds = new HashSet<>();
        for (Map<String, Object> place : places) {
            String placeId = text(place, "placeId");
            if (!candidateRefs.contains(placeId) || excluded.contains(placeId)
                    || (filterAllowed != null && !filterAllowed.contains(placeId))
                    || !evidence.allowedPlaceIds().contains(placeId)) {
                throw invalid();
            }
            List<Map<String, Object>> claims = objects(place.get("reasonClaims"));
            if (claims.isEmpty() || claims.size() > MAX_CLAIMS) {
                throw invalid();
            }
            for (Map<String, Object> claim : claims) {
                validateClaim(claim, placeId, evidence, claimIds);
            }
        }
        List<Map<String, Object>> summaryClaims = objects(proposal.get("summaryClaims"));
        if (summaryClaims.size() > MAX_CLAIMS) {
            throw invalid();
        }
        for (Map<String, Object> claim : summaryClaims) {
            validateClaim(claim, null, evidence, claimIds);
        }

        return new JourneyWorkerPlan(placeIds, placeIds.isEmpty() ? "NO_RESULTS" : "PROPOSAL");
    }

    private static void validateClaim(Map<String, Object> claim, String expectedPlaceId,
                                      EvidenceIndex evidence, Set<String> claimIds) {
        if (!claimIds.add(text(claim, "claimId"))) {
            throw invalid();
        }
        if (text(claim, "text").length() > 1000 || text(claim, "kind").isBlank()) {
            throw invalid();
        }
        List<String> evidenceIds = strings(list(claim.get("evidenceIds")));
        if (evidenceIds.isEmpty() || evidenceIds.size() > MAX_CLAIM_EVIDENCE
                || evidenceIds.size() != new HashSet<>(evidenceIds).size()) {
            throw invalid();
        }
        for (String evidenceId : evidenceIds) {
            String owner = evidence.placeOf(evidenceId);
            if (owner == null || (expectedPlaceId != null && !expectedPlaceId.equals(owner))) {
                throw invalid();
            }
        }
    }

    /** Evidence set after structural checks: unique IDs/chunks, two chunks per place at most, places match. */
    private record EvidenceIndex(Map<String, String> placeByEvidenceId, Set<String> allowedPlaceIds) {

        static EvidenceIndex from(Map<String, Object> envelope) {
            List<String> allowed = strings(list(envelope.get("allowedPlaceIds")));
            if (allowed.size() != new HashSet<>(allowed).size()) {
                throw invalid();
            }
            List<Map<String, Object>> items = objects(envelope.get("evidence"));
            if (items.size() > MAX_EVIDENCE) {
                throw invalid();
            }
            Map<String, String> placeById = new HashMap<>();
            Set<String> chunks = new HashSet<>();
            Map<String, Integer> perPlace = new HashMap<>();
            for (Map<String, Object> item : items) {
                String evidenceId = text(item, "evidenceId");
                String place = text(item, "placeId");
                if (placeById.containsKey(evidenceId) || !chunks.add(text(item, "chunkId"))
                        || !allowed.contains(place)) {
                    throw invalid();
                }
                if (nonNegative(item, "endOffset") <= nonNegative(item, "startOffset")) {
                    throw invalid();
                }
                if (perPlace.merge(place, 1, Integer::sum) > 2) {
                    throw invalid();
                }
                placeById.put(evidenceId, place);
            }
            if (!new HashSet<>(allowed).equals(perPlace.keySet())) {
                throw invalid();
            }
            return new EvidenceIndex(placeById, new HashSet<>(allowed));
        }

        String placeOf(String evidenceId) {
            return placeByEvidenceId.get(evidenceId);
        }
    }

    private static void requireEnvelope(Map<String, Object> value, String schema, PlanProposal plan) {
        require(schema.equals(value.get("schemaVersion")));
        require(plan.runId().toString().equals(value.get("runId")));
        require(plan.planHash().equals(value.get("planHash")));
        require(plan.versions().equals(versions(value.get("versions"))));
    }

    private static Versions versions(Object value) {
        Map<String, Object> map = object(value);
        return new Versions(text(map, "corpusRevision"), text(map, "configHash"),
                text(map, "questionDictionaryVersion"), nonNegative(map, "sourceRevocationSequence"));
    }

    private static Map<String, Object> object(Object value) {
        if (!(value instanceof Map<?, ?> map)) {
            throw invalid();
        }
        Map<String, Object> copy = new HashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (!(entry.getKey() instanceof String key)) {
                throw invalid();
            }
            copy.put(key, entry.getValue());
        }
        return copy;
    }

    private static List<Map<String, Object>> objects(Object value) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object item : list(value)) {
            result.add(object(item));
        }
        return result;
    }

    private static List<Object> list(Object value) {
        if (value == null) {
            return List.of();
        }
        if (value instanceof List<?> list) {
            return new ArrayList<>(list);
        }
        throw invalid();
    }

    private static List<String> strings(List<?> values) {
        List<String> result = new ArrayList<>();
        for (Object value : values) {
            if (!(value instanceof String text) || text.isBlank()) {
                throw invalid();
            }
            result.add(text);
        }
        return result;
    }

    private static String text(Map<String, Object> map, String key) {
        if (!(map.get(key) instanceof String text) || text.isBlank()) {
            throw invalid();
        }
        return text;
    }

    private static long nonNegative(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (!(value instanceof Integer || value instanceof Long) || ((Number) value).longValue() < 0) {
            throw invalid();
        }
        return ((Number) value).longValue();
    }

    private static void require(boolean condition) {
        if (!condition) {
            throw invalid();
        }
    }

    private static AiProposalException invalid() {
        return new AiProposalException(WorkerDegradedReason.AI_INVALID_RESPONSE);
    }
}
