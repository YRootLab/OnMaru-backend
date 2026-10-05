package com.yrootlab.onmaru.journey.events;

import java.util.UUID;

public interface JourneyRunEventSink {

    JourneyRunEvent stage(UUID runId, String status, String stage);

    /** Narration is an optional notification, never a run state transition. */
    default JourneyRunEvent textDelta(UUID runId, String text) {
        return null;
    }

    JourneyRunEvent terminal(UUID runId, String status, String outcome);

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
