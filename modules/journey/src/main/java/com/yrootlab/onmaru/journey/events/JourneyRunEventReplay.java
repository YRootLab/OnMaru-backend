package com.yrootlab.onmaru.journey.events;

import java.util.List;

public record JourneyRunEventReplay(boolean resetRequired, List<JourneyRunEvent> events) {
}
