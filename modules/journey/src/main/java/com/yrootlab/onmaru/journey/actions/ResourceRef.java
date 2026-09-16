package com.yrootlab.onmaru.journey.actions;

public record ResourceRef(String type, String id) {
    public ResourceRef {
        if (!"PLACE".equals(type) || id == null || id.isBlank()) {
            throw new IllegalArgumentException("resourceRef must be a PLACE with an id");
        }
    }
}
