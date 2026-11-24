package com.yrootlab.onmaru.web.internal.mcp.journey;

import java.util.List;

public record JourneyQueryToolResponse(
        String catalogVersion,
        String status,
        List<ProjectedPlace> places) {

    public JourneyQueryToolResponse(List<ProjectedPlace> places) {
        this(
                "journey.query.tools.v1",
                places == null || places.isEmpty() ? "NO_MATCH" : "OK",
                places == null ? List.of() : places);
    }
}
