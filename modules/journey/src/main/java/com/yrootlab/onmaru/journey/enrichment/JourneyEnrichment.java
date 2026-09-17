package com.yrootlab.onmaru.journey.enrichment;

import com.yrootlab.onmaru.journey.actions.ResourceRef;

import java.util.Collections;
import java.util.List;

public record JourneyEnrichment(
        String schemaVersion,
        ResourceRef resourceRef,
        EnrichmentCoverageStatus coverageStatus,
        String summary,
        List<WhyRecommendedItem> whyRecommended,
        List<BestForItem> bestFor,
        List<VisitTipItem> visitTips,
        List<HanokHighlightItem> hanokHighlights,
        ReviewSummaryBlock reviewSummary,
        List<String> tags,
        List<String> unknowns,
        PresentationHints presentationHints) {

    public record WhyRecommendedItem(String text, List<String> evidenceRefs) {
    }

    public record BestForItem(String label, String reason) {
    }

    public record VisitTipItem(String label, String text, List<String> evidenceRefs) {
    }

    public record HanokHighlightItem(String label, String description, List<String> evidenceRefs) {
    }

    public record ReviewSummaryBlock(
            String coverage,
            int reviewCount,
            List<String> positive,
            List<String> cautions,
            List<String> evidenceRefs) {
    }

    public record PresentationHints(
            String intentType,
            String interestFocus,
            String answerDepth,
            String journeyContext,
            String decisionStage,
            String primaryMessage,
            String secondaryMessage,
            List<String> recommendedSections) {
    }

    public static JourneyEnrichment insufficientEvidence(ResourceRef resourceRef, String reason) {
        return new JourneyEnrichment(
                "1.2",
                resourceRef,
                EnrichmentCoverageStatus.INSUFFICIENT_EVIDENCE,
                null,
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList(),
                null,
                Collections.emptyList(),
                List.of(reason != null ? reason : "설명 생성에 충분한 근거가 없습니다."),
                null);
    }
}
