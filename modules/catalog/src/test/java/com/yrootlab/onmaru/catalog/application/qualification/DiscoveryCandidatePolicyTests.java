package com.yrootlab.onmaru.catalog.application.qualification;

import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;
import static com.yrootlab.onmaru.catalog.application.qualification.DiscoveryCandidatePolicy.*;

class DiscoveryCandidatePolicyTests {
    private final DiscoveryCandidatePolicy policy = new DiscoveryCandidatePolicy();

    @Test void frozenRepresentativeCases() {
        assertCase(row("126508", "경복궁", "HS010100"), Status.INCLUDE, Role.CORE_TRADITIONAL_PLACE, "STRONG_TAXONOMY_PENDING_DETAIL_GATE");
        assertCase(row("2", "북촌 고택", "HS010400"), Status.INCLUDE, Role.CORE_TRADITIONAL_PLACE, "STRONG_TAXONOMY_PENDING_DETAIL_GATE");
        assertCase(row("3", "한옥 카페", "FD050100"), Status.REVIEW, null, "BROAD_CLASS_TITLE_SIGNAL_NEEDS_EVIDENCE");
        assertCase(row("4", "가는곶 세화", "FD050100"), Status.EXCLUDE, null, "BROAD_CLASS_NO_TRADITION_SIGNAL");
        assertCase(row("5", "전통 체험마을", "EX030100"), Status.REVIEW, null, "BROAD_CLASS_TITLE_SIGNAL_NEEDS_EVIDENCE");
        assertCase(row("6", "전통 사찰", "HS020100"), Status.INCLUDE, Role.CORE_TRADITIONAL_PLACE, "CONDITIONAL_TITLE_SIGNAL_PENDING_DETAIL_GATE");
        assertCase(row("7", "서원", "HS020300"), Status.INCLUDE, Role.CORE_TRADITIONAL_PLACE, "CONDITIONAL_TITLE_SIGNAL_PENDING_DETAIL_GATE");
        assertCase(row("8", "사찰", "HS020100"), Status.REVIEW, null, "CONDITIONAL_NEEDS_CONTEXT");
        assertCase(row("9", "락고재 서울 북촌 한옥호텔", "AC010100"), Status.REVIEW, null, "KEYWORD_RESCUE_NEEDS_EVIDENCE");
        assertCase(row("10", "한양도성 안 궁궐과 학교이야기", "C01150001"), Status.EXCLUDE, null, "RESCUE_NO_PLACE_EVIDENCE");
        assertCase(row("11", "한옥", "ZZ999999"), Status.REVIEW, null, "KEYWORD_RESCUE_NEEDS_EVIDENCE");
    }

    @Test void invalidCoordinatesAndDuplicateAreConservative() {
        SourceRecord invalid = new SourceRecord("tourapi", "list", Map.of("contentid", "1", "title", "경복궁", "lclsSystm3", "HS010100", "mapx", "NaN", "mapy", "37.5"));
        assertThat(policy.qualify(invalid).reasonCode()).isEqualTo("SOURCE_INVALID");
        assertThat(policy.qualify(row("1", "경복궁", "HS010100"), null, true).reasonCode()).isEqualTo("DUPLICATE_IDENTITY");
        assertThat(policy.qualify(row("1", "경복궁", "HS010100"))).isEqualTo(policy.qualify(row("1", "경복궁", "HS010100")));
    }

    @Test void humanDecisionSurvivesSyncAndSemanticConflictGoesToReview() {
        SourceRecord original = row("1", "한옥 카페", "FD050100");
        HumanDecision human = new HumanDecision(Status.INCLUDE, Role.SURROUNDING_CULTURE, fingerprint(original));
        assertThat(policy.qualify(original, human, false).status()).isEqualTo(Status.INCLUDE);
        Decision changed = policy.qualify(row("1", "일반 카페", "FD050100"), human, false);
        assertThat(changed.status()).isEqualTo(Status.REVIEW);
        assertThat(changed.role()).isEqualTo(Role.SURROUNDING_CULTURE);
        assertThat(changed.reasonCode()).isEqualTo("HUMAN_DECISION_CONFLICT");
    }

    private void assertCase(SourceRecord row, Status status, Role role, String reason) {
        Decision decision = policy.qualify(row);
        assertThat(decision.status()).isEqualTo(status);
        assertThat(decision.role()).isEqualTo(role);
        assertThat(decision.reasonCode()).isEqualTo(reason);
        assertThat(decision.policyVersion()).isEqualTo(VERSION);
        assertThat(decision.publicationApproved()).isFalse();
    }

    private SourceRecord row(String id, String title, String code) {
        return new SourceRecord("tourapi", "list", Map.of("contentid", id, "title", title, "lclsSystm3", code, "mapx", "126.97", "mapy", "37.58"));
    }
}
