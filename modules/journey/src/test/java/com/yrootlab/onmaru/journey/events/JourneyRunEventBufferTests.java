package com.yrootlab.onmaru.journey.events;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.Callable;

import static org.assertj.core.api.Assertions.assertThat;

class JourneyRunEventBufferTests {

    @Test
    void textDeltaSharesStageTerminalSequenceAndReplaysWithoutChangingStageState() {
        var runId = UUID.randomUUID();
        var buffer = new JourneyRunEventBuffer(8, Duration.ofSeconds(15));
        var stage = buffer.stage(runId, "RUNNING", "INTERPRETING");
        String stageData = stage.data();

        var delta = textDelta(buffer, runId, "전주의 한옥을 잇고 있어요.");
        var terminal = buffer.terminal(runId, "COMPLETED", "INITIAL_BOARD");

        assertThat(delta.type().wireName()).isEqualTo("run.text.delta");
        assertThat(delta.id()).isEqualTo(2);
        assertThat(delta.closeAfterSend()).isFalse();
        assertThat(delta.data()).isEqualTo("{\"schemaVersion\":\"1.2\",\"runId\":\"" + runId
                + "\",\"sequence\":2,\"text\":\"전주의 한옥을 잇고 있어요.\"}");
        assertThat(stage.data()).isEqualTo(stageData);
        assertThat(terminal.id()).isEqualTo(3);
        assertThat(buffer.replay(runId, stage.id()).events()).containsExactly(delta, terminal);
    }

    @Test
    void liveSubscriberReceivesNarrationBeforeTerminal() throws Exception {
        var runId = UUID.randomUUID();
        var buffer = new JourneyRunEventBuffer(8, Duration.ofSeconds(15));
        var received = new java.util.ArrayList<JourneyRunEvent>();
        try (var subscription = buffer.subscribe(runId, received::add)) {
            var delta = textDelta(buffer, runId, "첫 narration");
            assertThat(received).containsExactly(delta);
            var terminal = buffer.terminal(runId, "COMPLETED", "INITIAL_BOARD");
            assertThat(received).containsExactly(delta, terminal);
        }
    }

    @Test
    void boundsOneDeltaTo512UnicodeCharactersWithoutSplittingAnEmoji() {
        var runId = UUID.randomUUID();
        var buffer = new JourneyRunEventBuffer(8, Duration.ofSeconds(15));

        var delta = textDelta(buffer, runId, "🙂".repeat(600));

        assertThat(delta.data()).isEqualTo("{\"schemaVersion\":\"1.2\",\"runId\":\"" + runId
                + "\",\"sequence\":1,\"text\":\"" + "🙂".repeat(512) + "\"}");
    }

    @Test
    void runBudgetRemains4000UnicodeCodePointsEvenAfterReplayEviction() {
        var runId = UUID.randomUUID();
        var buffer = new JourneyRunEventBuffer(2, Duration.ofSeconds(15));
        var acceptedDeltas = new java.util.ArrayList<JourneyRunEvent>();
        String chunk = "가🙂𐐷".repeat(170) + "나🙂";
        for (int i = 0; i < 7; i++) { acceptedDeltas.add(textDelta(buffer, runId, chunk)); }

        var finalDelta = textDelta(buffer, runId, "나🙂".repeat(256));
        acceptedDeltas.add(finalDelta);
        var ignored = textDelta(buffer, runId, "다");

        assertThat(finalDelta.id()).isEqualTo(8);
        assertThat(finalDelta.data()).endsWith("\"text\":\"" + "나🙂".repeat(208) + "\"}");
        assertThat(acceptedDeltas.stream()
                .map(event -> event.data().substring(event.data().indexOf("\"text\":\"") + 8,
                        event.data().length() - 2))
                .mapToInt(text -> text.codePointCount(0, text.length())).sum()).isEqualTo(4000);
        assertThat(ignored).isNull();
        assertThat(buffer.heartbeat(runId).id()).isEqualTo(8);
        assertThat(buffer.replay(runId, 1L).resetRequired()).isTrue();
        assertThat(textDelta(buffer, UUID.randomUUID(), "독립 run")).isNotNull();
    }

    @Test
    void dropsEmptyAndLateDeltasWithoutAdvancingSequenceOrChangingTerminal() {
        var runId = UUID.randomUUID();
        var buffer = new JourneyRunEventBuffer(8, Duration.ofSeconds(15));
        assertThat(textDelta(buffer, runId, null)).isNull();
        assertThat(textDelta(buffer, runId, "")).isNull();
        assertThat(buffer.heartbeat(runId).id()).isZero();
        var terminal = buffer.terminal(runId, "CANCELLED", null);

        assertThat(textDelta(buffer, runId, "늦은 narration")).isNull();
        assertThat(buffer.replay(runId, null).events()).containsExactly(terminal);
        assertThat(buffer.terminal(runId, "CANCELLED", null)).isEqualTo(terminal);
    }

    @Test
    void terminalStillClosesNarrationWhenOtherNotificationsEvictItsReplayFrame() {
        var runId = UUID.randomUUID();
        var buffer = new JourneyRunEventBuffer(1, Duration.ofSeconds(15));
        buffer.terminal(runId, "CANCELLED", null);
        buffer.stage(runId, "RUNNING", "INTERPRETING");
        assertThat(textDelta(buffer, runId, "늦은 narration")).isNull();
        assertThat(buffer.heartbeat(runId).id()).isEqualTo(2);
    }

    @Test
    void escapesJsonControlsInNarration() {
        var runId = UUID.randomUUID();
        var buffer = new JourneyRunEventBuffer(8, Duration.ofSeconds(15));

        var delta = textDelta(buffer, runId, "한옥 \"길\"\n\t\\골목\r\b\f\u0001");

        assertThat(delta.data()).isEqualTo("{\"schemaVersion\":\"1.2\",\"runId\":\"" + runId
                + "\",\"sequence\":1,\"text\":\"한옥 \\\"길\\\"\\n\\t\\\\골목\\r\\b\\f\\u0001\"}");
    }

    @Test
    void concurrentDeltasRespectRunBudgetAndMonotonicReplayOrder() throws Exception {
        var runId = UUID.randomUUID();
        var buffer = new JourneyRunEventBuffer(32, Duration.ofSeconds(15));
        String chunk = "가🙂𐐷".repeat(170) + "나🙂";
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var tasks = java.util.stream.IntStream.range(0, 20)
                    .mapToObj(ignored -> (Callable<JourneyRunEvent>) () -> textDelta(buffer, runId, chunk))
                    .toList();
            for (var future : executor.invokeAll(tasks)) { future.get(); }
        }
        var events = buffer.replay(runId, null).events();
        assertThat(events).hasSize(8);
        assertThat(events).extracting(JourneyRunEvent::id).containsExactly(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L);
        assertThat(events.stream()
                .map(event -> event.data().substring(event.data().indexOf("\"text\":\"") + 8,
                        event.data().length() - 2))
                .mapToInt(text -> text.codePointCount(0, text.length())).sum()).isEqualTo(4000);
    }

    @Test
    void clearResetsTheEphemeralNarrationBudget() {
        var runId = UUID.randomUUID();
        var buffer = new JourneyRunEventBuffer(2, Duration.ofSeconds(15));
        for (int i = 0; i < 8; i++) { textDelta(buffer, runId, "가".repeat(512)); }
        buffer.clear();
        assertThat(textDelta(buffer, runId, "새 narration").id()).isEqualTo(1);
    }

    private static JourneyRunEvent textDelta(JourneyRunEventSink sink, UUID runId, String text) {
        return sink.textDelta(runId, text);
    }

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
