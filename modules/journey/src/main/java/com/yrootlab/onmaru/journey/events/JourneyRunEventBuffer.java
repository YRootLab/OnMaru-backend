package com.yrootlab.onmaru.journey.events;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

public class JourneyRunEventBuffer implements JourneyRunEventSink {

    private final int capacity;
    private final Duration heartbeatInterval;
    private final Map<UUID, ArrayDeque<JourneyRunEvent>> eventsByRun = new ConcurrentHashMap<>();
    private final Map<UUID, AtomicLong> sequencesByRun = new ConcurrentHashMap<>();
    private final Map<UUID, List<Consumer<JourneyRunEvent>>> subscribersByRun = new ConcurrentHashMap<>();

    public JourneyRunEventBuffer(int capacity, Duration heartbeatInterval) {
        if (capacity < 1) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        if (heartbeatInterval == null || heartbeatInterval.isNegative() || heartbeatInterval.isZero()) {
            throw new IllegalArgumentException("heartbeat interval must be positive");
        }
        this.capacity = capacity;
        this.heartbeatInterval = heartbeatInterval;
    }

    @Override
    public JourneyRunEvent stage(UUID runId, String status, String stage) {
        var sequence = nextSequence(runId);
        return append(runId, new JourneyRunEvent(
                sequence,
                JourneyRunEventType.STAGE,
                stageData(runId, sequence, status, stage),
                heartbeatInterval,
                false));
    }

    @Override
    public JourneyRunEvent terminal(UUID runId, String status, String outcome) {
        var existing = latestTerminal(runId);
        if (existing != null && existing.data().contains("\"status\":\"" + escape(status) + "\"")
                && existing.data().contains(outcome == null ? "\"outcome\":null" : "\"outcome\":\"" + escape(outcome) + "\"")) {
            return existing;
        }
        var sequence = nextSequence(runId);
        return append(runId, new JourneyRunEvent(
                sequence,
                JourneyRunEventType.TERMINAL,
                terminalData(runId, sequence, status, outcome),
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

    public void clear() {
        eventsByRun.clear();
        sequencesByRun.clear();
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

    private String terminalData(UUID runId, long sequence, String status, String outcome) {
        var outcomeValue = outcome == null ? "null" : "\"" + escape(outcome) + "\"";
        return "{\"schemaVersion\":\"1.2\",\"runId\":\"" + runId + "\",\"sequence\":" + sequence
                + ",\"status\":\"" + escape(status) + "\",\"outcome\":" + outcomeValue + "}";
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
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
