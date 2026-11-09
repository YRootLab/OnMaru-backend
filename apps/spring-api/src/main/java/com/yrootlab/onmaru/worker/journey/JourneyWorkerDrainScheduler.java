package com.yrootlab.onmaru.worker.journey;

import com.yrootlab.onmaru.journey.worker.JourneyWorkerRunner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Periodically drains QUEUED journey runs on this instance. Off by default so that test contexts never start
 * processing runs on their own; the production compose sets onmaru.journey.worker.drain-enabled=true.
 * Several instances can run this safely: each run is taken by a CAS claim in the worker.
 */
@Component
@ConditionalOnProperty(name = "onmaru.journey.worker.drain-enabled", havingValue = "true")
class JourneyWorkerDrainScheduler {

    private static final Logger LOGGER = LoggerFactory.getLogger(JourneyWorkerDrainScheduler.class);

    private final ObjectProvider<JourneyWorkerRunner> runner;

    JourneyWorkerDrainScheduler(ObjectProvider<JourneyWorkerRunner> runner) {
        this.runner = runner;
    }

    @Scheduled(fixedDelayString = "${onmaru.journey.worker.drain-interval-ms:1000}")
    void drain() {
        var available = runner.getIfAvailable();
        if (available == null) {
            return;
        }
        try {
            available.drain();
        } catch (RuntimeException exception) {
            // Keep the schedule alive; the failed run stays QUEUED or RUNNING and is handled by claim/sweeper rules.
            LOGGER.warn("journey worker drain failed", exception);
        }
    }
}
