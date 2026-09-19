package com.yrootlab.onmaru.journey.events;

public record JourneyRunEventStreamOpen(JourneyRunEventReplay replay, AutoCloseable subscription) {
}
