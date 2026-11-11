package com.yrootlab.onmaru.persistence.mapcollab;

import java.time.Instant;
import java.util.UUID;

public interface RoomSessionStore {

    void insertSession(UUID id, UUID roomId, UUID memberId, String instanceId, Instant connectedAt);

    void updateDisconnected(UUID id, Instant disconnectedAt, String endReason);

    static RoomSessionStore noOp() {
        return new RoomSessionStore() {
            @Override
            public void insertSession(UUID id, UUID roomId, UUID memberId, String instanceId, Instant connectedAt) {}

            @Override
            public void updateDisconnected(UUID id, Instant disconnectedAt, String endReason) {}
        };
    }
}
