package com.yrootlab.onmaru.audio.query;

import java.util.List;
import java.util.UUID;

public record OdiiActiveSnapshot(
        UUID revisionId,
        List<OdiiStoryProjection> stories) {

    public OdiiActiveSnapshot {
        if (revisionId == null) {
            throw new IllegalArgumentException("revisionId must not be null");
        }
        stories = stories == null ? List.of() : List.copyOf(stories);
    }
}
