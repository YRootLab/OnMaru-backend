package com.yrootlab.onmaru.integration.ai.security;

import java.time.Instant;

public record InternalAiTokenRequest(
        String requestId,
        String traceId,
        String runId,
        String revision,
        Instant deadlineAt) {

    public static InternalAiTokenRequest forJourneyProposal(
            String requestId,
            String traceId,
            String runId,
            String revision,
            Instant deadlineAt) {
        return new InternalAiTokenRequest(requestId, traceId, runId, revision, deadlineAt);
    }
}
