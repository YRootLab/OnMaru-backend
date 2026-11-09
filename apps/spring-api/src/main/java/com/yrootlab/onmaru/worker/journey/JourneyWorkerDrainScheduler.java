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
            var result = available.drain();
            if (result.processed() > 0) {
                // Counts only: no run ids, queries, or member data in logs.
                LOGGER.info("journey worker drained processed={} outcomes={}",
                        result.processed(), outcomeCounts(result));
            }
        } catch (RuntimeException exception) {
            // Keep the schedule alive; the failed run stays QUEUED or RUNNING and is handled by claim/sweeper rules.
            LOGGER.warn("journey worker drain failed", exception);
        }
    }

    private static java.util.Map<String, Long> outcomeCounts(
            com.yrootlab.onmaru.journey.worker.JourneyWorkerDrainResult result) {
        return result.outcomes().stream().collect(java.util.stream.Collectors.groupingBy(
                outcome -> outcome.name(), java.util.TreeMap::new, java.util.stream.Collectors.counting()));
    }
}
