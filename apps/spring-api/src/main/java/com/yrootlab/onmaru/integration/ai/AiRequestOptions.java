package com.yrootlab.onmaru.integration.ai;

/**
 * Optional fields the backend adds to AI proposal requests. Both default to OFF: the AI version that accepts
 * {@code candidatePolicy} must be deployed and smoke-checked first, and {@code useRag=true} must not be sent until
 * AI chooses the active corpus revision server-side (v0.1.3 rejects {@code useRag} without {@code corpusRevisionId}).
 */
public record AiRequestOptions(boolean sendCandidatePolicy, boolean useRag) {

    public static final AiRequestOptions OFF = new AiRequestOptions(false, false);
}
