package com.yrootlab.onmaru.web.internal.mcp.quality;

public record QualityPublicSourceStateRequest(
        String sourceId,
        String sourceRevision
) {
}
