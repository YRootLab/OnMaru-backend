package com.yrootlab.onmaru.journey.events;

import java.time.Duration;

public record JourneyRunEvent(
        long id,
        JourneyRunEventType type,
        String data,
        Duration retry,
        boolean closeAfterSend) {
}
