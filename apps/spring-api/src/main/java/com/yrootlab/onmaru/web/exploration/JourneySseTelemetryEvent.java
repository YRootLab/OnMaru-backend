package com.yrootlab.onmaru.web.exploration;

import com.yrootlab.onmaru.journey.events.JourneyRunEventReplay;

import java.util.UUID;

public record JourneySseTelemetryEvent(
        String name,
        UUID runId,
        Long lastEventId,
        JourneyRunEventReplay replay,
        String reason) {

    static JourneySseTelemetryEvent authClosed() {
        return new JourneySseTelemetryEvent("journey.sse.auth_closed", null, null, null, "AUTH_REQUIRED");
    }

    static JourneySseTelemetryEvent replayed(UUID runId, Long lastEventId, JourneyRunEventReplay replay) {
        return new JourneySseTelemetryEvent("journey.sse.replayed", runId, lastEventId, replay, null);
    }
}
