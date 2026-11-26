package com.yrootlab.onmaru.web.internal.ai.progress;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.UUID;

public record InternalAiProgressPayload(
        String schemaVersion,
        UUID runId,
        String planHash,
        int ordinal,
        String stage,
        String stepKey,
        String status,
        Integer count) {

    @JsonCreator
    public InternalAiProgressPayload(
            @JsonProperty("schemaVersion") String schemaVersion,
            @JsonProperty("runId") UUID runId,
            @JsonProperty("planHash") String planHash,
            @JsonProperty("ordinal") int ordinal,
            @JsonProperty("stage") String stage,
            @JsonProperty("stepKey") String stepKey,
            @JsonProperty("status") String status,
            @JsonProperty("count") Integer count) {
        this.schemaVersion = schemaVersion;
        this.runId = runId;
        this.planHash = planHash;
        this.ordinal = ordinal;
        this.stage = stage;
        this.stepKey = stepKey;
        this.status = status;
        this.count = count;
    }

    /** Step metadata is optional so callers from before the step contract keep working. */
    public InternalAiProgressPayload(
            String schemaVersion, UUID runId, String planHash, int ordinal, String stage) {
        this(schemaVersion, runId, planHash, ordinal, stage, null, null, null);
    }
}
