package com.yrootlab.onmaru.community.command.review;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class VisitReviewTagPolicyTests {

    private final VisitReviewTagPolicy policy = new VisitReviewTagPolicy();

    @Test
    void canonicalizesSupportedDisplaySyntaxAndDuplicates() {
        assertThat(policy.normalize(List.of("  #힐링  ", "#야경   명소", "Healing", "힐링")))
                .containsExactly("힐링", "야경 명소", "healing");
    }

    @ParameterizedTest
    @ValueSource(strings = {"####하이", "#하이.", "# 힐링", "야경\n명소", "야경\t명소"})
    void rejectsUnsupportedSyntax(String tag) {
        assertThatThrownBy(() -> policy.normalize(List.of(tag)))
                .isInstanceOfSatisfying(VisitReviewWarmthInvalidException.class, error -> {
                    assertThat(error.field()).isEqualTo("tags");
                    assertThat(error.reason()).isEqualTo("INVALID_TAG_FORMAT");
                    assertThat(error.index()).isZero();
                });
    }

    @Test
    void rejectsMoreThanFiveTags() {
        assertThatThrownBy(() -> policy.normalize(List.of("하나", "둘", "셋", "넷", "다섯", "여섯")))
                .isInstanceOfSatisfying(VisitReviewWarmthInvalidException.class, error -> {
                    assertThat(error.reason()).isEqualTo("TOO_MANY_TAGS");
                    assertThat(error.index()).isNull();
                });
    }

    @Test
    void rejectsNullAndEmptyTags() {
        assertThatThrownBy(() -> policy.normalize(java.util.Arrays.asList("힐링", null)))
                .isInstanceOfSatisfying(VisitReviewWarmthInvalidException.class, error -> {
                    assertThat(error.reason()).isEqualTo("TAG_REQUIRED");
                    assertThat(error.index()).isEqualTo(1);
                });
        assertThatThrownBy(() -> policy.normalize(List.of("   ")))
                .isInstanceOfSatisfying(VisitReviewWarmthInvalidException.class, error ->
                        assertThat(error.reason()).isEqualTo("TAG_REQUIRED"));
        assertThatThrownBy(() -> policy.normalize(List.of("#")))
                .isInstanceOfSatisfying(VisitReviewWarmthInvalidException.class, error ->
                        assertThat(error.reason()).isEqualTo("TAG_REQUIRED"));
    }

    @Test
    void validatesLengthByNormalizedCodePoints() {
        assertThat(policy.normalize(List.of("가".repeat(15))))
                .containsExactly("가".repeat(15));

        assertThatThrownBy(() -> policy.normalize(List.of("가".repeat(16))))
                .isInstanceOfSatisfying(VisitReviewWarmthInvalidException.class, error ->
                        assertThat(error.reason()).isEqualTo("TAG_TOO_LONG"));
    }

    @Test
    void normalizesUnicodeBeforeDeduplication() {
        assertThat(policy.normalize(List.of("힐링", "힐링")))
                .containsExactly("힐링");
    }

    @Test
    void treatsNullCollectionAsNoTags() {
        assertThat(policy.normalize(null)).isEmpty();
    }
}
