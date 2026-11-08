package com.yrootlab.onmaru.integration.ai.plan;

import com.yrootlab.onmaru.journey.worker.plan.AiJourneyContracts.*;
import com.yrootlab.onmaru.journey.worker.plan.PlanAuthorityGate;
import java.util.List;
import java.util.Set;

/** Spring never grants authority that exceeds the original safe-query bounds. */
public final class DefaultPlanAuthorityGate implements PlanAuthorityGate {
    private final AiPlanWireCodec codec;
    private static final Set<String> CONCEPTS = Set.of("HISTORY", "HISTORICAL_SIGNIFICANCE", "ARCHITECTURE", "CULTURAL_PRACTICE", "VISIT_POINT", "FAMILY_SUITABILITY", "QUIETNESS", "ACCESSIBILITY", "INDEPENDENCE_MOVEMENT", "JOSEON_PERIOD", "TRADITIONAL_MARKET", "HANOK", "K_CONTENT", "SOLO", "COUPLE", "FAMILY", "FRIENDS", "QUIET", "LIVELY", "WALKABLE");
    private static final Set<String> REASONS = Set.of("CANONICAL_FACT_ONLY", "EXACT_PUBLIC_TERM", "SINGLE_SEMANTIC_TOPIC", "COMPOSITE_QUERY", "CANONICAL_FACT_WITH_RETRIEVAL", "AMBIGUOUS_TARGET", "CONFLICTING_CONDITIONS", "UNSUPPORTED_SCOPE", "SEMANTIC_LOSS");
    public DefaultPlanAuthorityGate(AiPlanWireCodec codec) { this.codec = codec; }

    @Override public ApprovedPlanBinding approve(SafeQueryRequest request, PlanProposal plan, Versions current) {
        try {
            require("ai-safe-query.v1".equals(request.schemaVersion()) && "ai-plan-proposal.v1".equals(plan.schemaVersion())
                    && "ai-query-plan.v1".equals(plan.planVersion()));
            require(plan.runId().equals(request.runId()) && plan.versions().equals(request.versions()) && plan.versions().equals(current));
            require("questions-1".equals(current.questionDictionaryVersion()) && current.sourceRevocationSequence() >= 0);
            require(plan.limits().equals(request.limits()) && plan.limits().equals(new Limits(20,20,30,12,5)));
            require(codec.planHash(plan).equals(plan.planHash()));
            require(plan.reasonCodes().size() >= 1 && plan.reasonCodes().size() <= 32 && REASONS.containsAll(plan.reasonCodes()));
            unique(plan.reasonCodes()); unique(plan.requestedCanonicalFields());
            require(request.authorityBounds().allowedCanonicalFields().containsAll(plan.requestedCanonicalFields()));
            Filters proposed = plan.proposedFilters(), bounds = request.authorityBounds().filters();
            require(proposed.publicOnly() && bounds.publicOnly() && proposed.filterHash().equals(bounds.filterHash()));
            subset(proposed.placeIds(), bounds.placeIds()); subset(proposed.regionCodes(), bounds.regionCodes()); subset(proposed.categoryCodes(), bounds.categoryCodes());
            require(proposed.pinnedPlaceIds().equals(bounds.pinnedPlaceIds())
                    && proposed.excludedPlaceIds().containsAll(bounds.excludedPlaceIds()));
            require(java.util.Collections.disjoint(proposed.pinnedPlaceIds(), proposed.excludedPlaceIds()));
            require(proposed.placeIds() == null || proposed.placeIds().containsAll(proposed.pinnedPlaceIds()));
            var question = plan.questionFrame(); var expression = plan.retrievalExpression();
            require(Set.of("RECOMMEND","EXPLAIN","COMPARE","REFINE").contains(question.operation()));
            require(question.targetPlaceIds().size() <= 2 && expression.publicEntityIds().size() <= 2);
            require(question.subjectConceptCodes().size() <= 4 && expression.positiveConceptCodes().size() <= 4 && expression.negativeConceptCodes().size() <= 2);
            unique(question.targetPlaceIds()); unique(question.subjectConceptCodes()); unique(expression.positiveConceptCodes()); unique(expression.negativeConceptCodes()); unique(expression.publicEntityIds());
            require(Set.copyOf(question.targetPlaceIds()).equals(Set.copyOf(expression.publicEntityIds())));
            require(Set.copyOf(question.subjectConceptCodes()).equals(Set.copyOf(expression.positiveConceptCodes())));
            require(CONCEPTS.containsAll(question.subjectConceptCodes()) && CONCEPTS.containsAll(expression.negativeConceptCodes()));
            require(java.util.Collections.disjoint(expression.positiveConceptCodes(), expression.negativeConceptCodes()));
            require(java.util.Collections.disjoint(question.targetPlaceIds(), proposed.excludedPlaceIds()));
            require(proposed.placeIds() == null || proposed.placeIds().containsAll(question.targetPlaceIds()));
            var expectedMode = switch (plan.route()) { case SQL_ONLY, CLARIFY_OR_SCOPE -> RetrievalMode.NONE; case LEXICAL_ONLY -> RetrievalMode.LEXICAL_ONLY; case DENSE_ONLY -> RetrievalMode.DENSE_ONLY; case HYBRID -> RetrievalMode.HYBRID; case SQL_PLUS_RETRIEVAL -> plan.retrievalMode(); };
            require(plan.retrievalMode() == expectedMode && (plan.route() != Route.SQL_PLUS_RETRIEVAL || expectedMode != RetrievalMode.NONE));
            require(plan.route().requiresLookup() == !plan.requestedCanonicalFields().isEmpty());
            require((plan.retrievalMode() != RetrievalMode.NONE) == (plan.semanticQuery() != null));
            if (plan.semanticQuery() != null) require(!plan.semanticQuery().isBlank() && plan.semanticQuery().codePointCount(0,plan.semanticQuery().length()) <= 240);
            require((plan.route() == Route.CLARIFY_OR_SCOPE) == (plan.clarification() != null));
            if (plan.clarification() != null) {
                var pairs = java.util.Map.of("AMBIGUOUS_TARGET","CHOOSE_TARGET","CONFLICTING_CONDITIONS","RESOLVE_CONDITIONS","UNSUPPORTED_SCOPE","SUPPORTED_TRAVEL_SCOPE","SEMANTIC_LOSS","PRESERVE_MEANING");
                require(plan.clarification().questionCode().equals(pairs.get(plan.clarification().reason())));
            }
            return new ApprovedPlanBinding(plan);
        } catch (RuntimeException e) { throw AiPlanWireCodec.invalid(); }
    }
    private static <T> void unique(List<T> list) { require(list.size() == Set.copyOf(list).size()); }
    private static void subset(List<String> proposed, List<String> bounds) { if (proposed != null) unique(proposed); require(bounds == null || proposed != null && bounds.containsAll(proposed)); }
    private static void require(boolean value) { if (!value) throw AiPlanWireCodec.invalid(); }
}
