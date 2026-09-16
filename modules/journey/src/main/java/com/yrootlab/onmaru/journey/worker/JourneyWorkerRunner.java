package com.yrootlab.onmaru.journey.worker;

import java.util.ArrayList;
import java.util.List;

public final class JourneyWorkerRunner {

    private final JourneyWorkerQueue queue;
    private final JourneyWorkerProcessor processor;

    public JourneyWorkerRunner(JourneyWorkerQueue queue, JourneyWorkerProcessor processor) {
        this.queue = queue;
        this.processor = processor;
    }

    public JourneyWorkerDrainResult drain() {
        List<JourneyWorkerOutcome> outcomes = new ArrayList<>();
        var next = queue.poll();
        while (next.isPresent()) {
            outcomes.add(processor.process(next.get()));
            next = queue.poll();
        }
        return new JourneyWorkerDrainResult(outcomes.size(), outcomes);
    }
}
