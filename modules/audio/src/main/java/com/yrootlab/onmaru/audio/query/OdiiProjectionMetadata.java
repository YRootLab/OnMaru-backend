package com.yrootlab.onmaru.audio.query;

import java.util.Objects;

public record OdiiProjectionMetadata(String category, OdiiRegionRef region) {

    public OdiiProjectionMetadata {
        if (category == null || category.isBlank()) {
            throw new IllegalArgumentException("category must not be blank");
        }
        Objects.requireNonNull(region, "region");
    }
}
