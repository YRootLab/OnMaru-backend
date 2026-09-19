package com.yrootlab.onmaru.web.exploration;

import com.yrootlab.onmaru.journey.events.JourneyRunEvent;
import com.yrootlab.onmaru.journey.events.JourneyRunEventBuffer;
import org.springframework.beans.factory.DisposableBean;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

final class InMemoryJourneyRunEventStream extends JourneyRunEventBuffer implements DisposableBean {

    private final ScheduledExecutorService heartbeatExecutor = Executors.newSingleThreadScheduledExecutor(task -> {
        var thread = new Thread(task, "journey-sse-heartbeat");
        thread.setDaemon(true);
        return thread;
    });

    InMemoryJourneyRunEventStream() {
        super(64, Duration.ofSeconds(15));
    }

    AutoCloseable scheduleHeartbeat(UUID runId, Consumer<JourneyRunEvent> consumer) {
        ScheduledFuture<?> future = heartbeatExecutor.scheduleAtFixedRate(
                () -> consumer.accept(heartbeat(runId)),
                15,
                15,
                TimeUnit.SECONDS);
        return () -> future.cancel(false);
    }

    @Override
    public void destroy() {
        heartbeatExecutor.shutdownNow();
    }
}
