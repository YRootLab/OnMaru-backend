package com.yrootlab.onmaru.integration.ai;

import java.util.Set;

/**
 * Optional fields the backend adds to AI proposal requests. Everything defaults to OFF.
 *
 * <p>{@code useRag} switches RAG on for every request. {@code ragActorKeys} switches it on only for the listed
 * actors, which is how a canary is run without exposing all members. The AI server still has the final kill switch
 * ({@code ONMARU_RAG_ENABLED}); the backend never bypasses it.
 */
public record AiRequestOptions(boolean sendCandidatePolicy, boolean useRag, Set<String> ragActorKeys) {

    public static final AiRequestOptions OFF = new AiRequestOptions(false, false, Set.of());

    public AiRequestOptions {
        ragActorKeys = ragActorKeys == null ? Set.of() : Set.copyOf(ragActorKeys);
    }

    public AiRequestOptions(boolean sendCandidatePolicy, boolean useRag) {
        this(sendCandidatePolicy, useRag, Set.of());
    }

    /** Whether this request should ask AI for RAG, given the actor that started it. */
    public boolean ragFor(String actorKey) {
        return useRag || (actorKey != null && ragActorKeys.contains(actorKey));
    }
}
