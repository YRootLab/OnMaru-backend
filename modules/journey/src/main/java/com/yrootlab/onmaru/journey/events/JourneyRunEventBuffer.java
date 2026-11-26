package com.yrootlab.onmaru.journey.events;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

public class JourneyRunEventBuffer implements JourneyRunEventSink {

    private final int capacity;
    private final Duration heartbeatInterval;
    private final Map<UUID, ArrayDeque<JourneyRunEvent>> eventsByRun = new ConcurrentHashMap<>();
    private final Map<UUID, AtomicLong> sequencesByRun = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> narrationCharactersByRun = new ConcurrentHashMap<>();
    private final Set<UUID> terminalRuns = ConcurrentHashMap.newKeySet();
    private final boolean runItemsEnabled;
    private final Map<UUID, OpenItem> openItemByRun = new ConcurrentHashMap<>();
    private final Set<UUID> runsWithStepProgress = ConcurrentHashMap.newKeySet();
    private final Map<UUID, List<Consumer<JourneyRunEvent>>> subscribersByRun = new ConcurrentHashMap<>();

    public JourneyRunEventBuffer(int capacity, Duration heartbeatInterval) {
        this(capacity, heartbeatInterval, false);
    }

    /**
     * @param runItemsEnabled when true, stage transitions also emit {@code run.item.*} frames that
     *                        describe the work items of a run. Disabled by default until clients support them.
     */
    public JourneyRunEventBuffer(int capacity, Duration heartbeatInterval, boolean runItemsEnabled) {
        if (capacity < 1) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        if (heartbeatInterval == null || heartbeatInterval.isNegative() || heartbeatInterval.isZero()) {
            throw new IllegalArgumentException("heartbeat interval must be positive");
        }
        this.capacity = capacity;
        this.heartbeatInterval = heartbeatInterval;
        this.runItemsEnabled = runItemsEnabled;
    }

    @Override
    public synchronized JourneyRunEvent stage(UUID runId, String status, String stage) {
        var sequence = nextSequence(runId);
        var event = append(runId, new JourneyRunEvent(
                sequence,
                JourneyRunEventType.STAGE,
                stageData(runId, sequence, status, stage),
                heartbeatInterval,
                false));
        if (runItemsEnabled && !runsWithStepProgress.contains(runId)) {
            transitionItem(runId, stage);
        }
        return event;
    }

    @Override
    public synchronized JourneyRunEvent textDelta(UUID runId, String text) {
        if (runId == null) {
            throw new IllegalArgumentException("run id is required");
        }
        if (text == null || text.isEmpty() || terminalRuns.contains(runId)) {
            return null;
        }
        int used = narrationCharactersByRun.getOrDefault(runId, 0);
        int accepted = Math.min(Math.min(512, 4000 - used), text.codePointCount(0, text.length()));
        if (accepted < 1) {
            return null;
        }
        String boundedText = text.substring(0, text.offsetByCodePoints(0, accepted));
        narrationCharactersByRun.put(runId, used + accepted);
        long sequence = nextSequence(runId);
        return append(runId, new JourneyRunEvent(sequence, JourneyRunEventType.TEXT_DELTA,
                "{\"schemaVersion\":\"1.2\",\"runId\":\"" + runId + "\",\"sequence\":" + sequence
                        + ",\"text\":\"" + escape(boundedText) + "\"}", heartbeatInterval, false));
    }

    @Override
    public synchronized JourneyRunEvent terminal(UUID runId, String status, String outcome) {
        return terminal(runId, status, outcome, null, 0L);
    }

    @Override
    public synchronized JourneyRunEvent terminal(
            UUID runId,
            String status,
            String outcome,
            String errorCode,
            long retryAfterMs) {
        String normalizedErrorCode = errorCode == null || errorCode.isBlank() ? null : errorCode;
        long normalizedRetryAfterMs = Math.max(0L, retryAfterMs);
        var existing = latestTerminal(runId);
        if (existing != null && existing.data().equals(terminalData(
                runId, existing.id(), status, outcome, normalizedErrorCode, normalizedRetryAfterMs))) {
            return existing;
        }
        if (runItemsEnabled) {
            closeOpenItem(runId, COMPLETED_STATUS.equals(status) ? "DONE" : "SKIPPED", null);
        }
        var sequence = nextSequence(runId);
        terminalRuns.add(runId);
        return append(runId, new JourneyRunEvent(
                sequence,
                JourneyRunEventType.TERMINAL,
                terminalData(runId, sequence, status, outcome, normalizedErrorCode, normalizedRetryAfterMs),
                heartbeatInterval,
                true));
    }

    public JourneyRunEventReplay replay(UUID runId, Long lastEventId) {
        return replaySnapshot(runId, lastEventId);
    }

    public synchronized JourneyRunEventStreamOpen open(UUID runId, Long lastEventId, Consumer<JourneyRunEvent> sender) {
        var replay = replaySnapshot(runId, lastEventId);
        for (var event : replay.events()) {
            sender.accept(event);
        }
        if (replay.events().stream().anyMatch(JourneyRunEvent::closeAfterSend)) {
            return new JourneyRunEventStreamOpen(replay, () -> {
            });
        }
        return new JourneyRunEventStreamOpen(replay, subscribe(runId, sender));
    }

    private JourneyRunEventReplay replaySnapshot(UUID runId, Long lastEventId) {
        var events = snapshot(runId);
        if (events.isEmpty()) {
            if (lastEventId != null) {
                return new JourneyRunEventReplay(true, List.of(reset(runId)));
            }
            return new JourneyRunEventReplay(false, List.of(heartbeat(runId)));
        }
        if (lastEventId != null && lastEventId < events.getFirst().id()) {
            return new JourneyRunEventReplay(true, List.of(reset(runId)));
        }
        var replay = new ArrayList<JourneyRunEvent>();
        for (var event : events) {
            if (lastEventId == null || event.id() > lastEventId) {
                replay.add(event);
            }
        }
        if (replay.isEmpty()) {
            replay.add(heartbeat(runId));
        }
        return new JourneyRunEventReplay(false, List.copyOf(replay));
    }

    public JourneyRunEvent authClosed() {
        return new JourneyRunEvent(0, JourneyRunEventType.AUTH_CLOSED, "0", null, true);
    }

    public synchronized JourneyRunEvent heartbeat(UUID runId) {
        var sequence = currentSequence(runId);
        return new JourneyRunEvent(
                sequence,
                JourneyRunEventType.HEARTBEAT,
                "{\"schemaVersion\":\"1.2\",\"runId\":\"" + runId + "\",\"sequence\":" + sequence + "}",
                heartbeatInterval,
                false);
    }

    public synchronized AutoCloseable subscribe(UUID runId, Consumer<JourneyRunEvent> subscriber) {
        if (runId == null || subscriber == null) {
            throw new IllegalArgumentException("run id and subscriber are required");
        }
        var subscribers = subscribersByRun.computeIfAbsent(runId, ignored -> new ArrayList<>());
        synchronized (subscribers) {
            subscribers.add(subscriber);
        }
        return () -> {
            synchronized (subscribers) {
                subscribers.remove(subscriber);
            }
        };
    }

    public synchronized void clear() {
        eventsByRun.clear();
        sequencesByRun.clear();
        narrationCharactersByRun.clear();
        terminalRuns.clear();
        subscribersByRun.clear();
    }

    private synchronized JourneyRunEvent append(UUID runId, JourneyRunEvent event) {
        if (runId == null) {
            throw new IllegalArgumentException("run id is required");
        }
        var events = eventsByRun.computeIfAbsent(runId, ignored -> new ArrayDeque<>());
        synchronized (events) {
            events.addLast(event);
            while (events.size() > capacity) {
                events.removeFirst();
            }
        }
        publish(runId, event);
        return event;
    }

    private List<JourneyRunEvent> snapshot(UUID runId) {
        var events = eventsByRun.get(runId);
        if (events == null) {
            return List.of();
        }
        synchronized (events) {
            return new ArrayList<>(events);
        }
    }

    private JourneyRunEvent latestTerminal(UUID runId) {
        var events = eventsByRun.get(runId);
        if (events == null) {
            return null;
        }
        synchronized (events) {
            var iterator = events.descendingIterator();
            while (iterator.hasNext()) {
                var event = iterator.next();
                if (event.type() == JourneyRunEventType.TERMINAL) {
                    return event;
                }
            }
            return null;
        }
    }

    private JourneyRunEvent reset(UUID runId) {
        return new JourneyRunEvent(
                0,
                JourneyRunEventType.RESET,
                "{\"schemaVersion\":\"1.2\",\"runId\":\"" + runId + "\"}",
                heartbeatInterval,
                true);
    }

    private String stageData(UUID runId, long sequence, String status, String stage) {
        var stageValue = stage == null ? "null" : "\"" + escape(stage) + "\"";
        return "{\"schemaVersion\":\"1.2\",\"runId\":\"" + runId + "\",\"sequence\":" + sequence
                + ",\"status\":\"" + escape(status) + "\",\"stage\":" + stageValue + "}";
    }

    private String terminalData(
            UUID runId,
            long sequence,
            String status,
            String outcome,
            String errorCode,
            long retryAfterMs) {
        var outcomeValue = outcome == null ? "null" : "\"" + escape(outcome) + "\"";
        var json = new StringBuilder();
        json.append("{\"schemaVersion\":\"1.2\",\"runId\":\"").append(runId)
                .append("\",\"sequence\":").append(sequence)
                .append(",\"status\":\"").append(escape(status))
                .append("\",\"outcome\":").append(outcomeValue);
        if (errorCode != null && !errorCode.isBlank()) {
            json.append(",\"errorCode\":\"").append(escape(errorCode)).append("\"");
        }
        if (retryAfterMs > 0L) {
            json.append(",\"retryAfterMs\":").append(retryAfterMs);
        }
        json.append("}");
        return json.toString();
    }

    private long nextSequence(UUID runId) {
        if (runId == null) {
            throw new IllegalArgumentException("run id is required");
        }
        return sequencesByRun.computeIfAbsent(runId, ignored -> new AtomicLong()).incrementAndGet();
    }

    private long currentSequence(UUID runId) {
        if (runId == null) {
            throw new IllegalArgumentException("run id is required");
        }
        var sequence = sequencesByRun.get(runId);
        if (sequence == null || sequence.get() < 1) {
            return 0;
        }
        return sequence.get();
    }

    private void publish(UUID runId, JourneyRunEvent event) {
        var subscribers = subscribersByRun.get(runId);
        if (subscribers == null) {
            return;
        }
        List<Consumer<JourneyRunEvent>> snapshot;
        synchronized (subscribers) {
            snapshot = List.copyOf(subscribers);
        }
        for (var subscriber : snapshot) {
            subscriber.accept(event);
        }
    }

    private String escape(String value) {
        if (value == null) {
            return "";
        }
        var escaped = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char character = value.charAt(i);
            switch (character) {
                case '"', '\\' -> escaped.append('\\').append(character);
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                case '\b' -> escaped.append("\\b");
                case '\f' -> escaped.append("\\f");
                default -> {
                    if (character < 0x20) {
                        escaped.append(String.format("\\u%04x", (int) character));
                    } else {
                        escaped.append(character);
                    }
                }
            }
        }
        return escaped.toString();
    }

    private static final Map<String, String> STEP_BY_STAGE = Map.of(
            "INTERPRETING", "UNDERSTAND",
            "RETRIEVING", "SEARCH",
            "SELECTING_EVIDENCE", "SELECT",
            "GENERATING", "WRITE",
            "VALIDATING", "CHECK");

    private static final String COMPLETED_STATUS = "COMPLETED";

    private record OpenItem(String itemId, String stepKey) {}

    @Override
    public synchronized JourneyRunEvent stepProgress(UUID runId, String stepKey, String status, Integer count) {
        if (!runItemsEnabled || runId == null || !STEP_KEYS.contains(stepKey) || !STEP_STATUSES.contains(status)) {
            return null;
        }
        runsWithStepProgress.add(runId);
        var open = openItemByRun.get(runId);
        if ("STARTED".equals(status)) {
            if (open != null && open.stepKey().equals(stepKey)) {
                return null;
            }
            closeOpenItem(runId, "DONE", null);
            return openItem(runId, stepKey);
        }
        // DONE or SKIPPED: make sure the item exists, then close it with the reported result.
        if (open == null || !open.stepKey().equals(stepKey)) {
            closeOpenItem(runId, "DONE", null);
            openItem(runId, stepKey);
        }
        var detail = "DONE".equals(status) && count != null && count >= 0 && COUNTED_STEPS.contains(stepKey)
                ? "{\"count\":" + count + "}"
                : null;
        return closeOpenItem(runId, status, detail);
    }

    private JourneyRunEvent openItem(UUID runId, String stepKey) {
        var sequence = nextSequence(runId);
        var itemId = stepKey + "-" + sequence;
        openItemByRun.put(runId, new OpenItem(itemId, stepKey));
        return append(runId, new JourneyRunEvent(
                sequence,
                JourneyRunEventType.ITEM_STARTED,
                itemStartedData(runId, sequence, itemId, stepKey),
                heartbeatInterval,
                false));
    }

    private static final Set<String> STEP_KEYS = Set.of("UNDERSTAND", "SEARCH", "SELECT", "WRITE", "CHECK");
    private static final Set<String> STEP_STATUSES = Set.of("STARTED", "DONE", "SKIPPED");
    private static final Set<String> COUNTED_STEPS = Set.of("SEARCH", "SELECT");

    /** Closes the item of the previous stage and opens the item of the new stage, if it has one. */
    private void transitionItem(UUID runId, String stage) {
        var next = stage == null ? null : STEP_BY_STAGE.get(stage);
        var open = openItemByRun.get(runId);
        if (open != null && open.stepKey().equals(next)) {
            return;
        }
        closeOpenItem(runId, "DONE", null);
        if (next != null) {
            openItem(runId, next);
        }
    }

    private JourneyRunEvent closeOpenItem(UUID runId, String status, String detail) {
        var open = openItemByRun.remove(runId);
        if (open == null) {
            return null;
        }
        var sequence = nextSequence(runId);
        return append(runId, new JourneyRunEvent(
                sequence,
                JourneyRunEventType.ITEM_COMPLETED,
                itemCompletedData(runId, sequence, open.itemId(), status, detail),
                heartbeatInterval,
                false));
    }

    private String itemStartedData(UUID runId, long sequence, String itemId, String stepKey) {
        return "{\"schemaVersion\":\"1.4\",\"runId\":\"" + runId + "\",\"sequence\":" + sequence
                + ",\"itemId\":\"" + escape(itemId) + "\",\"stepKey\":\"" + escape(stepKey) + "\"}";
    }

    private String itemCompletedData(UUID runId, long sequence, String itemId, String status, String detail) {
        return "{\"schemaVersion\":\"1.4\",\"runId\":\"" + runId + "\",\"sequence\":" + sequence
                + ",\"itemId\":\"" + escape(itemId) + "\",\"status\":\"" + escape(status)
                + "\",\"detail\":" + (detail == null ? "{}" : detail) + "}";
    }
}
