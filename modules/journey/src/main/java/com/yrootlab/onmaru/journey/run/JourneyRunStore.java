package com.yrootlab.onmaru.journey.run;

import java.util.Optional;
import java.util.UUID;

public interface JourneyRunStore {
    RunCommandResult create(CreateRunCommand command);
    RunCommandResult claim(ClaimRunCommand command);
    RunCommandResult advance(AdvanceRunStageCommand command);
    RunCommandResult finish(FinishRunCommand command);
    Optional<JourneyRunSnapshot> find(UUID runId, String actorKey);
}
