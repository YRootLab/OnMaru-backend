package com.yrootlab.onmaru.journey.observation;

import java.util.Optional;
import java.util.UUID;

public interface RunObservationQueryPort {

    Optional<RunObservation> findByRunId(UUID runId);
}
