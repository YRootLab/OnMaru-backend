package com.yrootlab.onmaru.journey.cancellation;

import com.yrootlab.onmaru.journey.run.JourneyRunSnapshot;
import com.yrootlab.onmaru.journey.run.JourneyRunStore;
import com.yrootlab.onmaru.journey.run.RunCommandResult;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public final class JourneyRunCancellationService {

    private final JourneyRunStore store;

    public JourneyRunCancellationService(JourneyRunStore store) {
        this.store = store;
    }

    public RunCommandResult cancel(CancelJourneyRunCommand command) {
        if (command == null || command.commandKey() == null || command.actorKey() == null || command.actorKey().isBlank()
                || command.runId() == null || command.requestHash() == null || command.requestHash().isBlank()
                || command.cancelledAt() == null) {
            throw new IllegalArgumentException("run cancellation command is invalid");
        }
        return store.cancel(command);
    }

    public List<JourneyRunSnapshot> expireDueRuns(Instant now) {
        if (now == null) {
            throw new IllegalArgumentException("sweep time is required");
        }
        return store.expireDueRuns(now);
    }

    public Optional<JourneyRunSnapshot> find(UUID runId, String actorKey) {
        if (runId == null || actorKey == null || actorKey.isBlank()) {
            return Optional.empty();
        }
        return store.find(runId, actorKey);
    }
}
