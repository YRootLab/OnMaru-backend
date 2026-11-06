package com.yrootlab.onmaru.web.internal.mcp.journey;

import com.yrootlab.onmaru.catalog.application.query.journey.ApprovedPlaceLookupItem;

import java.util.List;

public record JourneyQueryToolResponse(
        String catalogVersion,
        List<ApprovedPlaceLookupItem> places) {

    public JourneyQueryToolResponse(List<ApprovedPlaceLookupItem> places) {
        this("journey.query.tools.v1", places);
    }
}
