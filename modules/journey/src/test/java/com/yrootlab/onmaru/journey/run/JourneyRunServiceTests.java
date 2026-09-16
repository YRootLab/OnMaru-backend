package com.yrootlab.onmaru.journey.run;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JourneyRunServiceTests {

    private final JourneyRunService service = new JourneyRunService(new RejectingStore());

    @Test
    void rejectsSkippedStageBeforeCallingPersistence() {
        assertThatThrownBy(() -> service.advance(new AdvanceRunStageCommand(
                UUID.randomUUID(), "actor:member:1", UUID.randomUUID(), "hash",
                2, null, JourneyRunStage.RETRIEVING)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("stage");
    }

    @Test
    void rejectsNonTerminalFinishStatusBeforeCallingPersistence() {
        assertThatThrownBy(() -> service.finish(new FinishRunCommand(
                UUID.randomUUID(), "actor:member:1", UUID.randomUUID(), "hash",
                2, JourneyRunStatus.RUNNING, "BOARD_READY", null, Instant.now())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("terminal");
    }

    private static final class RejectingStore implements JourneyRunStore {
        @Override
        public RunCommandResult create(CreateRunCommand command) {
            throw new AssertionError("store must not be called");
        }

        @Override
        public RunCommandResult claim(ClaimRunCommand command) {
            throw new AssertionError("store must not be called");
        }

        @Override
        public RunCommandResult advance(AdvanceRunStageCommand command) {
            throw new AssertionError("store must not be called");
        }

        @Override
        public RunCommandResult finish(FinishRunCommand command) {
            throw new AssertionError("store must not be called");
        }

        @Override
        public java.util.Optional<JourneyRunSnapshot> find(UUID runId, String actorKey) {
            return java.util.Optional.empty();
        }
    }
}
