package com.yrootlab.onmaru.journey.observation;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record RunObservation(
        String schemaVersion,
        UUID runId,
        String traceId,
        ObservationStatus observationStatus,
        TraceCoverage traceCoverage,
        ObservationRoute route,
        ObservationOutcome outcome,
        String corpusRevision,
        String configHash,
        String promptVersion,
        List<StageObservation> stages
) {
    public static final String SCHEMA_VERSION = "run-observation.v1";

    public RunObservation {
        schemaVersion = SCHEMA_VERSION;
        Objects.requireNonNull(runId, "runId must not be null");
        Objects.requireNonNull(observationStatus, "observationStatus must not be null");
        Objects.requireNonNull(traceCoverage, "traceCoverage must not be null");
        Objects.requireNonNull(route, "route must not be null");
        Objects.requireNonNull(outcome, "outcome must not be null");
        stages = stages == null ? List.of() : List.copyOf(stages);
    }

    public static Builder builder(UUID runId) {
        return new Builder(runId);
    }

    public static final class Builder {
        private final UUID runId;
        private String traceId;
        private ObservationStatus observationStatus = ObservationStatus.COMPLETE;
        private TraceCoverage traceCoverage = TraceCoverage.AVAILABLE;
        private ObservationRoute route = ObservationRoute.UNKNOWN;
        private ObservationOutcome outcome = ObservationOutcome.UNKNOWN;
        private String corpusRevision;
        private String configHash;
        private String promptVersion;
        private List<StageObservation> stages = List.of();

        public Builder(UUID runId) {
            this.runId = Objects.requireNonNull(runId, "runId must not be null");
        }

        public Builder traceId(String traceId) {
            this.traceId = traceId;
            return this;
        }

        public Builder observationStatus(ObservationStatus status) {
            this.observationStatus = status;
            return this;
        }

        public Builder traceCoverage(TraceCoverage traceCoverage) {
            this.traceCoverage = traceCoverage;
            return this;
        }

        public Builder route(ObservationRoute route) {
            this.route = route;
            return this;
        }

        public Builder outcome(ObservationOutcome outcome) {
            this.outcome = outcome;
            return this;
        }

        public Builder corpusRevision(String corpusRevision) {
            this.corpusRevision = corpusRevision;
            return this;
        }

        public Builder configHash(String configHash) {
            this.configHash = configHash;
            return this;
        }

        public Builder promptVersion(String promptVersion) {
            this.promptVersion = promptVersion;
            return this;
        }

        public Builder stages(List<StageObservation> stages) {
            this.stages = stages == null ? List.of() : List.copyOf(stages);
            return this;
        }

        public RunObservation build() {
            return new RunObservation(
                    SCHEMA_VERSION,
                    runId,
                    traceId,
                    observationStatus,
                    traceCoverage,
                    route,
                    outcome,
                    corpusRevision,
                    configHash,
                    promptVersion,
                    stages
            );
        }
    }
}
