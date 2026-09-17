package com.yrootlab.onmaru.journey.thread;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface JourneyThreadStore {

    JourneyThread saveOrUpdate(JourneyThread thread);

    void recordTurn(JourneyTurnMemory turn);

    List<JourneyThread> list(UUID memberId);

    Optional<JourneyThread> find(UUID memberId, UUID threadId);

    Optional<JourneyThread> findByExplorationId(UUID explorationId);

    List<JourneyTurnMemory> listTurns(UUID threadId);

    void delete(UUID memberId, UUID threadId, Instant deletedAt);

    void deleteAllForMember(UUID memberId, Instant deletedAt);
}
