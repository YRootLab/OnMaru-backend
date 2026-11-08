package com.yrootlab.onmaru.web.internal.mcp.journey;

/** Typed failure body; never echoes the grant reference, secrets, or internal detail. */
public record QueryToolError(String code) {
}
