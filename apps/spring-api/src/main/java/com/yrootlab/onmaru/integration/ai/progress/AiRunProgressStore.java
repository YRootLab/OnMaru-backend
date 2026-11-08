package com.yrootlab.onmaru.integration.ai.progress;

import com.yrootlab.onmaru.journey.run.JourneyRunStage;

import java.util.Optional;
import java.util.UUID;

/** Durable projection of AI progress. Callbacks never write the worker-owned run generation or stage. */
public interface AiRunProgressStore {

    enum Decision { APPLIED, DUPLICATE, STALE, INACTIVE, CONFLICT, PLAN_MISMATCH, NOT_BOUND }

    /**
     * @param decision outcome of the ordinal compare-and-set
     * @param publish stage to publish to SSE only when the effective run stage advanced; otherwise null
     */
    record Result(Decision decision, JourneyRunStage publish) {
        public Result {
            if (decision == null) throw new IllegalArgumentException("decision is required");
            if (publish != null && decision != Decision.APPLIED) throw new IllegalArgumentException("only applied progress publishes");
        }
    }

    Optional<String> boundPlanHash(UUID runId);

    Result record(UUID runId, String planHash, int ordinal, JourneyRunStage stage);
}
