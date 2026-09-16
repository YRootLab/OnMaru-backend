package com.yrootlab.onmaru.journey.actions;

import java.util.LinkedHashSet;
import java.util.List;

public record JourneyActionState(
        int stateVersion,
        List<ResourceRef> pinnedRefs,
        List<ResourceRef> excludedRefs,
        List<ResourceRef> orderedRefs,
        List<JourneyLeg> legs,
        JourneyProposal pendingProposal) {

    public JourneyActionState {
        if (stateVersion < 0) {
            throw new IllegalArgumentException("stateVersion must not be negative");
        }
        pinnedRefs = immutableDistinct(pinnedRefs, 3, "pinnedRefs");
        excludedRefs = immutableDistinct(excludedRefs, 100, "excludedRefs");
        orderedRefs = immutableDistinct(orderedRefs, 3, "orderedRefs");
        legs = List.copyOf(legs);
    }

    public static JourneyActionState empty(int stateVersion) {
        return new JourneyActionState(stateVersion, List.of(), List.of(), List.of(), List.of(), null);
    }

    public JourneyActionState withPendingProposal(JourneyProposal proposal) {
        return new JourneyActionState(stateVersion, pinnedRefs, excludedRefs, orderedRefs, legs, proposal);
    }

    private static <T> List<T> immutableDistinct(List<T> values, int maximum, String field) {
        if (values == null || values.size() > maximum || new LinkedHashSet<>(values).size() != values.size()) {
            throw new IllegalArgumentException(field + " is invalid");
        }
        return List.copyOf(values);
    }
}
