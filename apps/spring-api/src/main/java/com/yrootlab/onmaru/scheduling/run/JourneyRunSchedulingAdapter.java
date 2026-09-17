package com.yrootlab.onmaru.scheduling.run;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public final class JourneyRunSchedulingAdapter {

    private final ObjectProvider<JourneyRunSweeper> sweeperProvider;

    JourneyRunSchedulingAdapter(ObjectProvider<JourneyRunSweeper> sweeperProvider) {
        this.sweeperProvider = sweeperProvider;
    }

    @Scheduled(fixedDelayString = "${onmaru.journey.run.sweep-delay-ms:1000}")
    public void sweepExpiredRuns() {
        var sweeper = sweeperProvider.getIfAvailable();
        if (sweeper != null) {
            sweeper.sweep();
        }
    }
}
