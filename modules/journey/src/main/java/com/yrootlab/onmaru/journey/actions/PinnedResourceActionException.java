package com.yrootlab.onmaru.journey.actions;

public final class PinnedResourceActionException extends RuntimeException {
    private final ResourceRef resourceRef;

    public PinnedResourceActionException(ResourceRef resourceRef) {
        super("pinned resource cannot be excluded: " + resourceRef.id());
        this.resourceRef = resourceRef;
    }

    public ResourceRef resourceRef() { return resourceRef; }
}
