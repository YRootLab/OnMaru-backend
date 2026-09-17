package com.yrootlab.onmaru.catalog.application.tags;

public record ContentTagOverride(
        String label,
        ContentTagOverrideAction action
) {

    public ContentTagOverride {
        label = label == null ? "" : label.trim();
        if (label.isBlank()) {
            throw new IllegalArgumentException("label must not be blank");
        }
        if (action == null) {
            throw new IllegalArgumentException("action must not be null");
        }
    }

    public static ContentTagOverride pin(String label) {
        return new ContentTagOverride(label, ContentTagOverrideAction.PIN);
    }

    public static ContentTagOverride hide(String label) {
        return new ContentTagOverride(label, ContentTagOverrideAction.HIDE);
    }
}
