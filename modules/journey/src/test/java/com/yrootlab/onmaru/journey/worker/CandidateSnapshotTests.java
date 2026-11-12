package com.yrootlab.onmaru.journey.worker;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CandidateSnapshotTests {

    @Test
    void sameInputGivesSameIdentifier() {
        var first = CandidateSnapshot.id("v1", "kr-11", "rev-1", List.of("p-1", "p-2"));
        var second = CandidateSnapshot.id("v1", "kr-11", "rev-1", List.of("p-1", "p-2"));

        assertThat(first).isEqualTo(second).hasSize(CandidateSnapshot.ID_LENGTH);
    }

    @Test
    void orderPolicyRegionAndRevisionEachChangeTheIdentifier() {
        var base = CandidateSnapshot.id("v1", "kr-11", "rev-1", List.of("p-1", "p-2"));

        assertThat(CandidateSnapshot.id("v1", "kr-11", "rev-1", List.of("p-2", "p-1"))).isNotEqualTo(base);
        assertThat(CandidateSnapshot.id("v2", "kr-11", "rev-1", List.of("p-1", "p-2"))).isNotEqualTo(base);
        assertThat(CandidateSnapshot.id("v1", null, "rev-1", List.of("p-1", "p-2"))).isNotEqualTo(base);
        assertThat(CandidateSnapshot.id("v1", "kr-11", "rev-2", List.of("p-1", "p-2"))).isNotEqualTo(base);
    }

    @Test
    void emptyCandidateListStillGetsAStableIdentifier() {
        var empty = CandidateSnapshot.id("v1", null, "rev-1", List.of());

        assertThat(empty).hasSize(CandidateSnapshot.ID_LENGTH);
        assertThat(empty).isEqualTo(CandidateSnapshot.id("v1", null, "rev-1", List.of()));
    }
}
