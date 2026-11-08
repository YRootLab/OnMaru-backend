package com.yrootlab.onmaru.web.internal.mcp.journey;

import java.util.List;

public record JourneyQueryToolResponse(
        String catalogVersion,
        List<ProjectedPlace> places) {

    public JourneyQueryToolResponse(List<ProjectedPlace> places) {
        this("journey.query.tools.v1", places);
    }
}
