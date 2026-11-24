package com.yrootlab.onmaru.integration.ai.plan;

import com.yrootlab.onmaru.journey.worker.plan.AiJourneyContracts.Versions;
import com.yrootlab.onmaru.journey.worker.plan.JourneyPlanExecutionPorts.CurrentVersions;
import java.util.function.LongSupplier;

import java.util.Objects;

public final class ConfigurableCurrentVersions implements CurrentVersions {

    private final Versions pinnedVersions;
    private final LongSupplier sourceWatermark;

    public ConfigurableCurrentVersions(AiPlanVersionsProperties properties) {
        this(properties, () -> properties.sourceRevocationSequence());
    }

    public ConfigurableCurrentVersions(AiPlanVersionsProperties properties, LongSupplier sourceWatermark) {
        Objects.requireNonNull(properties, "properties must not be null");
        this.pinnedVersions = new Versions(
                properties.corpusRevision(),
                properties.configHash(),
                properties.questionDictionaryVersion(),
                0L);
        this.sourceWatermark = Objects.requireNonNull(sourceWatermark, "sourceWatermark must not be null");
    }

    @Override
    public Versions current() {
        return new Versions(
                pinnedVersions.corpusRevision(), pinnedVersions.configHash(),
                pinnedVersions.questionDictionaryVersion(), sourceWatermark.getAsLong());
    }
}
