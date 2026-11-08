package com.yrootlab.onmaru.web.internal.mcp.journey;

import com.fasterxml.jackson.annotation.JsonInclude;

/** Only fields requested by the approved plan are non-null; everything else is omitted on the wire. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ProjectedPlace(
        String placeId,
        String publicAddress,
        String regionCode,
        String categoryCode,
        String publicStatus) {
}
