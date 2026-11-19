package com.yrootlab.onmaru.integration.ai.plan;

import com.yrootlab.onmaru.journey.worker.plan.AiJourneyContracts.Versions;
import com.yrootlab.onmaru.journey.worker.plan.JourneyPlanExecutionPorts.CurrentVersions;

import java.util.Objects;

public final class ConfigurableCurrentVersions implements CurrentVersions {

    private final Versions versions;

    public ConfigurableCurrentVersions(AiPlanVersionsProperties properties) {
        Objects.requireNonNull(properties, "properties must not be null");
        this.versions = new Versions(
                properties.corpusRevision(),
                properties.configHash(),
                properties.questionDictionaryVersion(),
                properties.sourceRevocationSequence());
    }

    @Override
    public Versions current() {
        return versions;
    }
}
