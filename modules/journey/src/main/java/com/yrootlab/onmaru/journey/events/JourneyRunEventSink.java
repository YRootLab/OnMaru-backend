package com.yrootlab.onmaru.journey.events;

import java.util.UUID;

public interface JourneyRunEventSink {

    JourneyRunEvent stage(UUID runId, String status, String stage);

    /** Narration is an optional notification, never a run state transition. */
    default JourneyRunEvent textDelta(UUID runId, String text) {
        return null;
    }

    /**
     * AI-reported step transition. {@code status} is STARTED, DONE, or SKIPPED; {@code count} is only
     * meaningful for SEARCH and SELECT completions. Default is a no-op for sinks without item frames.
     */
    default JourneyRunEvent stepProgress(UUID runId, String stepKey, String status, Integer count) {
        return null;
    }

    JourneyRunEvent terminal(UUID runId, String status, String outcome);

    default JourneyRunEvent terminal(UUID runId, String status, String outcome, String errorCode, long retryAfterMs) {
        return terminal(runId, status, outcome);
    }

    static JourneyRunEventSink noop() {
        return new JourneyRunEventSink() {
            @Override
            public JourneyRunEvent stage(UUID runId, String status, String stage) {
                return null;
            }

            @Override
            public JourneyRunEvent terminal(UUID runId, String status, String outcome) {
                return null;
            }
        };
    }
}
