package com.yrootlab.onmaru.web.insights;

import com.yrootlab.onmaru.insights.ingestion.DataLabCollectionObserver;
import com.yrootlab.onmaru.insights.ingestion.DataLabCollectionOutcome;
import com.yrootlab.onmaru.insights.ingestion.DataLabCollectionReason;
import io.micrometer.core.instrument.MeterRegistry;

import java.util.Locale;
import java.util.Objects;

final class MicrometerDataLabCollectionObserver implements DataLabCollectionObserver {

    private final MeterRegistry meterRegistry;

    MicrometerDataLabCollectionObserver(MeterRegistry meterRegistry) {
        this.meterRegistry = Objects.requireNonNull(meterRegistry);
    }

    @Override
    public void record(DataLabCollectionOutcome outcome, DataLabCollectionReason reason) {
        meterRegistry.counter(
                "onmaru.datalab.collection",
                "outcome", outcome.name().toLowerCase(Locale.ROOT),
                "reason", reason == null ? "none" : reason.name().toLowerCase(Locale.ROOT)
        ).increment();
    }
}
