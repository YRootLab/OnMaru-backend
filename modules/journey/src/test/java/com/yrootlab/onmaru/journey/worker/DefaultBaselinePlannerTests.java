package com.yrootlab.onmaru.journey.worker;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DefaultBaselinePlannerTests {

    private final DefaultBaselinePlanner planner = new DefaultBaselinePlanner();

    @Test
    void selectsFirstThreeCandidateRefsAsInitialBoardWithoutReordering() {
        var plan = planner.plan(new CandidatePayload(
                "dataset-2026-09-16",
                List.of(
                        new JourneyCandidate("place:001"),
                        new JourneyCandidate("place:002"),
                        new JourneyCandidate("place:003"),
                        new JourneyCandidate("place:004"))));

        assertThat(plan.outcome()).isEqualTo("INITIAL_BOARD");
        assertThat(plan.orderedRefs()).containsExactly("place:001", "place:002", "place:003");
    }

    @Test
    void returnsNoResultsWhenCandidatePayloadIsEmpty() {
        var plan = planner.plan(new CandidatePayload("dataset-2026-09-16", List.of()));

        assertThat(plan.outcome()).isEqualTo("NO_RESULTS");
        assertThat(plan.orderedRefs()).isEmpty();
    }
}
