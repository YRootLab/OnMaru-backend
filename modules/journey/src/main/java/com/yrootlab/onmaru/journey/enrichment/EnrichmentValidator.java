package com.yrootlab.onmaru.journey.enrichment;

import java.util.List;
import java.util.Set;

public final class EnrichmentValidator {

    private static final Set<String> ALLOWED_SOURCE_PREFIXES = Set.of(
            "catalog:",
            "hanok:",
            "odii:",
            "review:",
            "region:",
            "corpus:",
            "evidence-"
    );

    private EnrichmentValidator() {
    }

    public static boolean isValidEvidenceRef(String ref) {
        if (ref == null || ref.isBlank()) {
            return false;
        }
        for (var prefix : ALLOWED_SOURCE_PREFIXES) {
            if (ref.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    public static boolean validateEvidenceRefs(List<String> evidenceRefs) {
        if (evidenceRefs == null || evidenceRefs.isEmpty()) {
            return false;
        }
        for (var ref : evidenceRefs) {
            if (!isValidEvidenceRef(ref)) {
                return false;
            }
        }
        return true;
    }

    public static JourneyEnrichment validateOrFallback(JourneyEnrichment enrichment) {
        if (enrichment == null) {
            throw new IllegalArgumentException("enrichment cannot be null");
        }

        if (enrichment.coverageStatus() == EnrichmentCoverageStatus.INSUFFICIENT_EVIDENCE
                || enrichment.coverageStatus() == EnrichmentCoverageStatus.UNAVAILABLE) {
            return enrichment;
        }

        // Validate whyRecommended evidenceRefs
        if (enrichment.whyRecommended() != null) {
            for (var item : enrichment.whyRecommended()) {
                if (!validateEvidenceRefs(item.evidenceRefs())) {
                    return JourneyEnrichment.insufficientEvidence(
                            enrichment.resourceRef(),
                            "추천 이유에 검증되지 않은 출처가 포함되어 있습니다.");
                }
            }
        }

        // Validate visitTips evidenceRefs
        if (enrichment.visitTips() != null) {
            for (var tip : enrichment.visitTips()) {
                if (!validateEvidenceRefs(tip.evidenceRefs())) {
                    return JourneyEnrichment.insufficientEvidence(
                            enrichment.resourceRef(),
                            "방문 팁에 검증되지 않은 출처가 포함되어 있습니다.");
                }
            }
        }

        // Validate hanokHighlights evidenceRefs
        if (enrichment.hanokHighlights() != null) {
            for (var highlight : enrichment.hanokHighlights()) {
                if (!validateEvidenceRefs(highlight.evidenceRefs())) {
                    return JourneyEnrichment.insufficientEvidence(
                            enrichment.resourceRef(),
                            "한옥 특징에 검증되지 않은 출처가 포함되어 있습니다.");
                }
            }
        }

        // Validate reviewSummary evidenceRefs
        if (enrichment.reviewSummary() != null) {
            if (!validateEvidenceRefs(enrichment.reviewSummary().evidenceRefs())) {
                return JourneyEnrichment.insufficientEvidence(
                        enrichment.resourceRef(),
                        "후기 요약에 검증되지 않은 출처가 포함되어 있습니다.");
            }
        }

        return enrichment;
    }
}
