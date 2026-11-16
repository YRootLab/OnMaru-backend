package com.yrootlab.onmaru.scheduling.run;

import com.yrootlab.onmaru.journey.cancellation.JourneyRunCancellationService;

import java.time.Clock;
import java.util.Objects;
import java.util.function.Consumer;

public final class JourneyRunSweeper {

    private final JourneyRunCancellationService cancellationService;
    private final Clock clock;
    private final Consumer<String> onExpiredActorKey;

    public JourneyRunSweeper(JourneyRunCancellationService cancellationService, Clock clock) {
        this(cancellationService, clock, actorKey -> { });
    }

    public JourneyRunSweeper(
            JourneyRunCancellationService cancellationService,
            Clock clock,
            Consumer<String> onExpiredActorKey) {
        this.cancellationService = Objects.requireNonNull(cancellationService, "cancellationService");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.onExpiredActorKey = Objects.requireNonNull(onExpiredActorKey, "onExpiredActorKey");
    }

    public int sweep() {
        var expired = cancellationService.expireDueRuns(clock.instant());
        expired.forEach(snapshot -> onExpiredActorKey.accept(snapshot.actorKey()));
        return expired.size();
    }
}
