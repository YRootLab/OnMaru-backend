package com.yrootlab.onmaru.journey.events;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class JourneyRunEventBufferTests {

    @Test
    void replayReturnsOnlyEventsAfterLastEventIdWithMonotonicIds() {
        var runId = UUID.randomUUID();
        var buffer = new JourneyRunEventBuffer(8, Duration.ofSeconds(15));

        var queued = buffer.stage(runId, "QUEUED", null);
        var running = buffer.stage(runId, "RUNNING", "INTERPRETING");
        var terminal = buffer.terminal(runId, "COMPLETED", "INITIAL_BOARD");

        var replay = buffer.replay(runId, queued.id());

        assertThat(running.id()).isGreaterThan(queued.id());
        assertThat(terminal.id()).isGreaterThan(running.id());
        assertThat(replay.resetRequired()).isFalse();
        assertThat(replay.events()).extracting(JourneyRunEvent::type)
                .containsExactly(JourneyRunEventType.STAGE, JourneyRunEventType.TERMINAL);
        assertThat(replay.events()).extracting(JourneyRunEvent::id)
                .containsExactly(running.id(), terminal.id());
        assertThat(replay.events().getFirst().data()).contains(
                "\"schemaVersion\":\"1.2\"",
                "\"runId\":\"" + runId + "\"",
                "\"sequence\":" + running.id());
    }

    @Test
    void bufferMissReturnsResetInstructionInsteadOfPartialReplay() {
        var runId = UUID.randomUUID();
        var buffer = new JourneyRunEventBuffer(2, Duration.ofSeconds(15));

        var evicted = buffer.stage(runId, "QUEUED", null);
        buffer.stage(runId, "RUNNING", "INTERPRETING");
        buffer.terminal(runId, "COMPLETED", "INITIAL_BOARD");

        var replay = buffer.replay(runId, evicted.id());

        assertThat(replay.resetRequired()).isTrue();
        assertThat(replay.events()).singleElement().satisfies(event -> {
            assertThat(event.type()).isEqualTo(JourneyRunEventType.RESET);
            assertThat(event.data()).isEqualTo("{\"schemaVersion\":\"1.2\",\"runId\":\"" + runId + "\"}");
            assertThat(event.closeAfterSend()).isTrue();
        });
    }

    @Test
    void emptyReplayEmitsHeartbeatWithoutAdvancingSequence() {
        var buffer = new JourneyRunEventBuffer(8, Duration.ofSeconds(15));

        var runId = UUID.randomUUID();
        var replay = buffer.replay(runId, null);

        assertThat(replay.events()).singleElement().satisfies(event -> {
            assertThat(event.type()).isEqualTo(JourneyRunEventType.HEARTBEAT);
            assertThat(event.id()).isZero();
            assertThat(event.retry()).isEqualTo(Duration.ofSeconds(15));
            assertThat(event.data()).isEqualTo(
                    "{\"schemaVersion\":\"1.2\",\"runId\":\"" + runId + "\",\"sequence\":" + event.id() + "}");
        });

        var firstStage = buffer.stage(runId, "QUEUED", null);

        assertThat(firstStage.id()).isEqualTo(1);
    }

    @Test
    void reconnectAfterRestartReturnsResetWhenLastEventIdHasNoBuffer() {
        var runId = UUID.randomUUID();
        var buffer = new JourneyRunEventBuffer(8, Duration.ofSeconds(15));

        var replay = buffer.replay(runId, 3L);

        assertThat(replay.resetRequired()).isTrue();
        assertThat(replay.events()).singleElement().satisfies(event -> {
            assertThat(event.type()).isEqualTo(JourneyRunEventType.RESET);
            assertThat(event.id()).isZero();
            assertThat(event.data()).isEqualTo("{\"schemaVersion\":\"1.2\",\"runId\":\"" + runId + "\"}");
        });
    }

    @Test
    void subscriberReceivesFutureEventsUntilClosed() throws Exception {
        var runId = UUID.randomUUID();
        var buffer = new JourneyRunEventBuffer(8, Duration.ofSeconds(15));
        var received = new java.util.ArrayList<JourneyRunEvent>();

        var subscription = buffer.subscribe(runId, received::add);
        var running = buffer.stage(runId, "RUNNING", "INTERPRETING");
        subscription.close();
        buffer.terminal(runId, "COMPLETED", "INITIAL_BOARD");

        assertThat(received).extracting(JourneyRunEvent::id).containsExactly(running.id());
    }

    @Test
    void terminalEventIsIdempotentForSameStatusOutcome() {
        var runId = UUID.randomUUID();
        var buffer = new JourneyRunEventBuffer(8, Duration.ofSeconds(15));

        var first = buffer.terminal(runId, "COMPLETED", "INITIAL_BOARD");
        var replay = buffer.terminal(runId, "COMPLETED", "INITIAL_BOARD");

        assertThat(replay.id()).isEqualTo(first.id());
        assertThat(buffer.replay(runId, null).events()).singleElement()
                .satisfies(event -> assertThat(event.id()).isEqualTo(first.id()));
    }
}
