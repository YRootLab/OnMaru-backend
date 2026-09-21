package com.yrootlab.onmaru.audio.query;

import java.util.List;

public record OdiiRegionGroup(
        String label,
        List<String> regionCodes,
        long storyCount) {

    public OdiiRegionGroup {
        regionCodes = List.copyOf(regionCodes);
    }
}
