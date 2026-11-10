package com.yrootlab.onmaru.web.internal.mcp.journey;

import java.util.List;

/**
 * Lookup result. {@code status} is {@code NO_MATCH} exactly when {@code places} is empty, because the AI
 * contract rejects an empty {@code places} list with any other status.
 */
public record JourneyQueryToolResponse(
        String schemaVersion,
        String catalogVersion,
        String status,
        List<ProjectedPlace> places) {

    static final String VERSION = "journey.query.tools.v1";

    public static JourneyQueryToolResponse of(List<ProjectedPlace> places) {
        String status = places.isEmpty() ? "NO_MATCH" : "OK";
        return new JourneyQueryToolResponse(VERSION, VERSION, status, places);
    }
}
