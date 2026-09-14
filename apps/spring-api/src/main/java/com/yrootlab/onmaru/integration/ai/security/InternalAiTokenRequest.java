package com.yrootlab.onmaru.integration.ai.security;

import java.time.Instant;

public record InternalAiTokenRequest(
        String requestId,
        String traceId,
        String runId,
        Instant deadlineAt) {

    public static InternalAiTokenRequest forJourneyProposal(
            String requestId,
            String traceId,
            String runId,
            Instant deadlineAt) {
        return new InternalAiTokenRequest(requestId, traceId, runId, deadlineAt);
    }
}
