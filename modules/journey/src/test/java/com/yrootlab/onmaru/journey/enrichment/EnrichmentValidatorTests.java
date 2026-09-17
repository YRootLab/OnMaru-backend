package com.yrootlab.onmaru.journey.enrichment;

import com.yrootlab.onmaru.journey.actions.ResourceRef;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class EnrichmentValidatorTests {

    @Test
    @DisplayName("유효한 evidenceRefs를 가진 enrichment는 그대로 통과한다")
    void validEnrichmentPasses() {
        var ref = new ResourceRef("PLACE", "p-jeonju-hanok-village");
        var enrichment = new JourneyEnrichment(
                "1.2",
                ref,
                EnrichmentCoverageStatus.SUPPORTED,
                "전주 한옥마을 요약",
                List.of(new JourneyEnrichment.WhyRecommendedItem("조용한 한옥", List.of("catalog:p-jeonju", "review:quiet"))),
                List.of(new JourneyEnrichment.BestForItem("산책", "도보 최적")),
                List.of(new JourneyEnrichment.VisitTipItem("팁", "오전 방문 권장", List.of("review:time"))),
                List.of(new JourneyEnrichment.HanokHighlightItem("기와", "지붕선", List.of("catalog:hanok"))),
                new JourneyEnrichment.ReviewSummaryBlock("PARTIAL", 10, List.of("좋음"), List.of(), List.of("review:cluster")),
                List.of("한옥", "전주"),
                List.of(),
                null);

        var validated = EnrichmentValidator.validateOrFallback(enrichment);
        assertThat(validated.coverageStatus()).isEqualTo(EnrichmentCoverageStatus.SUPPORTED);
        assertThat(validated.whyRecommended()).hasSize(1);
    }

    @Test
    @DisplayName("허용되지 않은 출처가 포함된 경우 INSUFFICIENT_EVIDENCE로 fallback된다")
    void invalidSourceTriggersFallback() {
        var ref = new ResourceRef("PLACE", "p-jeonju-hanok-village");
        var enrichment = new JourneyEnrichment(
                "1.2",
                ref,
                EnrichmentCoverageStatus.SUPPORTED,
                "전주 한옥마을 요약",
                List.of(new JourneyEnrichment.WhyRecommendedItem("조용한 한옥", List.of("untrusted_source:xyz"))),
                List.of(),
                List.of(),
                List.of(),
                null,
                List.of(),
                List.of(),
                null);

        var validated = EnrichmentValidator.validateOrFallback(enrichment);
        assertThat(validated.coverageStatus()).isEqualTo(EnrichmentCoverageStatus.INSUFFICIENT_EVIDENCE);
        assertThat(validated.summary()).isNull();
        assertThat(validated.unknowns()).isNotEmpty();
    }
}
