package com.yrootlab.onmaru.web.internal.ai.progress;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.UUID;

public record InternalAiProgressPayload(
        String schemaVersion,
        UUID runId,
        String planHash,
        int ordinal,
        String stage) {

    @JsonCreator
    public InternalAiProgressPayload(
            @JsonProperty("schemaVersion") String schemaVersion,
            @JsonProperty("runId") UUID runId,
            @JsonProperty("planHash") String planHash,
            @JsonProperty("ordinal") int ordinal,
            @JsonProperty("stage") String stage) {
        this.schemaVersion = schemaVersion;
        this.runId = runId;
        this.planHash = planHash;
        this.ordinal = ordinal;
        this.stage = stage;
    }
}
