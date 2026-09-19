package com.yrootlab.onmaru.journey.thread;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class InMemoryJourneyThreadStore implements JourneyThreadStore {

    private final Map<UUID, JourneyThread> threadsById = new ConcurrentHashMap<>();
    private final Map<UUID, List<JourneyTurnMemory>> turnsByThreadId = new ConcurrentHashMap<>();

    private static final Comparator<JourneyThread> THREAD_COMPARATOR = Comparator
            .comparing(JourneyThread::updatedAt, Comparator.nullsLast(Comparator.reverseOrder()))
            .thenComparing(t -> t.threadId().toString(), Comparator.reverseOrder());

    private static final Comparator<JourneyTurnMemory> TURN_COMPARATOR = Comparator
            .comparing(JourneyTurnMemory::createdAt, Comparator.nullsLast(Comparator.naturalOrder()));

    @Override
    public JourneyThread saveOrUpdate(JourneyThread thread) {
        threadsById.put(thread.threadId(), thread);
        return thread;
    }

    @Override
    public void recordTurn(JourneyTurnMemory turn) {
        turnsByThreadId.computeIfAbsent(turn.threadId(), id -> Collections.synchronizedList(new ArrayList<>()))
                .add(turn);
    }

    @Override
    public List<JourneyThread> list(UUID memberId) {
        if (memberId == null) {
            return List.of();
        }
        return threadsById.values().stream()
                .filter(t -> memberId.equals(t.memberId()) && !t.isDeleted())
                .sorted(THREAD_COMPARATOR)
                .toList();
    }

    @Override
    public Optional<JourneyThread> find(UUID memberId, UUID threadId) {
        if (memberId == null || threadId == null) {
            return Optional.empty();
        }
        var thread = threadsById.get(threadId);
        if (thread == null || !memberId.equals(thread.memberId()) || thread.isDeleted()) {
            return Optional.empty();
        }
        return Optional.of(thread);
    }

    @Override
    public Optional<JourneyThread> findByExplorationId(UUID explorationId) {
        if (explorationId == null) {
            return Optional.empty();
        }
        return threadsById.values().stream()
                .filter(t -> explorationId.equals(t.explorationId()) && !t.isDeleted())
                .findFirst();
    }

    @Override
    public List<JourneyTurnMemory> listTurns(UUID threadId) {
        if (threadId == null) {
            return List.of();
        }
        var list = turnsByThreadId.get(threadId);
        if (list == null) {
            return List.of();
        }
        synchronized (list) {
            return list.stream().sorted(TURN_COMPARATOR).toList();
        }
    }

    @Override
    public void delete(UUID memberId, UUID threadId, Instant deletedAt) {
        if (memberId == null || threadId == null) {
            return;
        }
        var thread = threadsById.get(threadId);
        if (thread != null && memberId.equals(thread.memberId())) {
            threadsById.put(threadId, thread.markDeleted(deletedAt != null ? deletedAt : Instant.now()));
        }
    }

    @Override
    public void deleteAllForMember(UUID memberId, Instant deletedAt) {
        if (memberId == null) {
            return;
        }
        var now = deletedAt != null ? deletedAt : Instant.now();
        for (var entry : threadsById.entrySet()) {
            if (memberId.equals(entry.getValue().memberId())) {
                entry.setValue(entry.getValue().markDeleted(now));
            }
        }
    }
}
