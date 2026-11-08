package com.yrootlab.onmaru.journey.worker.plan;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Immutable wire values shared by the transport and Spring authority boundary. */
public final class AiJourneyContracts {
    private AiJourneyContracts() { }

    public enum Route { SQL_ONLY, LEXICAL_ONLY, DENSE_ONLY, HYBRID, SQL_PLUS_RETRIEVAL, CLARIFY_OR_SCOPE;
        public boolean requiresLookup() { return this == SQL_ONLY || this == SQL_PLUS_RETRIEVAL; }
    }
    public enum RetrievalMode { NONE, LEXICAL_ONLY, DENSE_ONLY, HYBRID }
    public enum CanonicalField { PUBLIC_ADDRESS, REGION_CODE, CATEGORY_CODE, PUBLIC_STATUS }
    public record Versions(String corpusRevision, String configHash, String questionDictionaryVersion,
                           long sourceRevocationSequence) { }
    public record Limits(int bm25K, int vectorK, int rerankK, int evidenceK, int maxPlaces) { }
    public record Filters(String filterHash, boolean publicOnly, List<String> placeIds,
                          List<String> regionCodes, List<String> categoryCodes,
                          List<String> pinnedPlaceIds, List<String> excludedPlaceIds) {
        public Filters {
            placeIds = nullableCopy(placeIds); regionCodes = nullableCopy(regionCodes); categoryCodes = nullableCopy(categoryCodes);
            pinnedPlaceIds = List.copyOf(pinnedPlaceIds); excludedPlaceIds = List.copyOf(excludedPlaceIds);
        }
    }
    public record ConfirmedContext(String regionCode, List<String> pinnedPlaceIds, List<String> excludedPlaceIds,
                                   List<String> lastConfirmedPlaceIds) {
        public ConfirmedContext { pinnedPlaceIds = List.copyOf(pinnedPlaceIds); excludedPlaceIds = List.copyOf(excludedPlaceIds); lastConfirmedPlaceIds = List.copyOf(lastConfirmedPlaceIds); }
    }
    public record AuthorityBounds(Filters filters, List<CanonicalField> allowedCanonicalFields) {
        public AuthorityBounds { allowedCanonicalFields = List.copyOf(allowedCanonicalFields); }
    }
    public record SafeQueryRequest(String schemaVersion, UUID runId, String safeQuery, ConfirmedContext confirmedContext,
                                   AuthorityBounds authorityBounds, Versions versions, Limits limits) { }
    public record QuestionFrame(String operation, List<String> targetPlaceIds, List<String> subjectConceptCodes) {
        public QuestionFrame { targetPlaceIds = List.copyOf(targetPlaceIds); subjectConceptCodes = List.copyOf(subjectConceptCodes); }
    }
    public record RetrievalExpression(List<String> positiveConceptCodes, List<String> negativeConceptCodes, List<String> publicEntityIds) {
        public RetrievalExpression { positiveConceptCodes = List.copyOf(positiveConceptCodes); negativeConceptCodes = List.copyOf(negativeConceptCodes); publicEntityIds = List.copyOf(publicEntityIds); }
    }
    public record Clarification(String reason, String questionCode) { }
    public record PlanProposal(String schemaVersion, String planVersion, UUID runId, Route route,
                               RetrievalMode retrievalMode, List<String> reasonCodes, QuestionFrame questionFrame,
                               RetrievalExpression retrievalExpression, List<CanonicalField> requestedCanonicalFields,
                               Filters proposedFilters, String semanticQuery, Versions versions, Limits limits,
                               Clarification clarification, String planHash) {
        public PlanProposal { reasonCodes = List.copyOf(reasonCodes); requestedCanonicalFields = List.copyOf(requestedCanonicalFields); }
    }
    /** Created only after authority validation; execute must echo this exact proposal. */
    public record ApprovedPlanBinding(PlanProposal plan) {
        public ApprovedQueryPlan forExecution(String queryGrantId) {
            if (plan.route().requiresLookup() != (queryGrantId != null && !queryGrantId.isBlank()))
                throw new IllegalArgumentException("query grant does not match route");
            return new ApprovedQueryPlan("ai-approved-plan.v1", plan.planVersion(), plan.runId(), plan.planHash(),
                    plan.route(), plan.retrievalMode(), plan.proposedFilters(), plan.requestedCanonicalFields(),
                    plan.semanticQuery(), plan.versions(), queryGrantId);
        }
    }
    public record ApprovedQueryPlan(String schemaVersion, String planVersion, UUID runId, String approvedPlanHash,
                                    Route approvedRoute, RetrievalMode approvedRetrievalMode, Filters approvedFilters,
                                    List<CanonicalField> approvedCanonicalFields, String semanticQuery,
                                    Versions versions, String queryGrantId) {
        public ApprovedQueryPlan { approvedCanonicalFields = List.copyOf(approvedCanonicalFields); }
    }
    /** Publication is delegated to the final validator/mapper (#811); these artifacts are untrusted. */
    public record ExecutionResult(String schemaVersion, UUID runId, String planHash, Versions versions,
                                   Map<String, Object> proposal, Map<String, Object> evidence) { }
    public record CallContext(String requestId, UUID runId, String traceId, Instant deadlineAt) { }
    private static <T> List<T> nullableCopy(List<T> values) { return values == null ? null : List.copyOf(values); }
}
