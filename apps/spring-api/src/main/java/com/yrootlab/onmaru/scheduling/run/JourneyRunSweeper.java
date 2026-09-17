package com.yrootlab.onmaru.scheduling.run;

import com.yrootlab.onmaru.journey.cancellation.JourneyRunCancellationService;

import java.time.Clock;

public final class JourneyRunSweeper {

    private final JourneyRunCancellationService cancellationService;
    private final Clock clock;

    public JourneyRunSweeper(JourneyRunCancellationService cancellationService, Clock clock) {
        this.cancellationService = cancellationService;
        this.clock = clock;
    }

    public int sweep() {
        return cancellationService.expireDueRuns(clock.instant()).size();
    }
}
