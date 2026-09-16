package com.yrootlab.onmaru.journey.actions;

public record ResourceAction(ActionType type, ResourceRef resourceRef) implements JourneyAction {
    public ResourceAction {
        if (type != ActionType.PIN && type != ActionType.UNPIN
                && type != ActionType.EXCLUDE && type != ActionType.UNEXCLUDE) {
            throw new IllegalArgumentException("resource action type is required");
        }
        if (resourceRef == null) {
            throw new IllegalArgumentException("resourceRef is required");
        }
    }
}
