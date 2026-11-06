package com.yrootlab.onmaru.web.internal.mcp.journey;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

public record JourneyQueryToolRequest(
        String queryGrantId,
        String regionCode,
        String category,
        Integer limit) {

    @JsonCreator
    public JourneyQueryToolRequest(
            @JsonProperty("queryGrantId") String queryGrantId,
            @JsonProperty("regionCode") String regionCode,
            @JsonProperty("category") String category,
            @JsonProperty("limit") Integer limit) {
        this.queryGrantId = queryGrantId;
        this.regionCode = regionCode;
        this.category = category;
        this.limit = limit != null ? limit : 5;
    }
}
