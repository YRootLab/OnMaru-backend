package com.yrootlab.onmaru.journey.run;

import com.yrootlab.onmaru.journey.cancellation.CancelJourneyRunCommand;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface JourneyRunStore {
    RunCommandResult create(CreateRunCommand command);
    RunCommandResult claim(ClaimRunCommand command);
    RunCommandResult advance(AdvanceRunStageCommand command);
    RunCommandResult finish(FinishRunCommand command);
    default RunCommandResult cancel(CancelJourneyRunCommand command) {
        throw new UnsupportedOperationException("run cancellation is not supported");
    }
    default List<JourneyRunSnapshot> expireDueRuns(Instant now) {
        throw new UnsupportedOperationException("run expiry sweep is not supported");
    }
    Optional<JourneyRunSnapshot> find(UUID runId, String actorKey);
}
