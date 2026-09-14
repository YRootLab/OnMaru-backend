package com.yrootlab.onmaru.web.common.idempotency;

import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

public final class InMemoryIdempotencyStore implements IdempotencyStorePort {

    private final Map<StoredCommandKey, StoredResponse> responses = new HashMap<>();

    @Override
    public synchronized IdempotentResponse execute(
            IdempotencyCommand command,
            Clock clock,
            Supplier<IdempotentResponse> handler) {
        var key = new StoredCommandKey(command.subjectId(), command.key().toString(), command.method(), command.path());
        var stored = responses.get(key);
        if (stored != null) {
            if (!Objects.equals(stored.payloadFingerprint(), command.payloadFingerprint())) {
                throw new IdempotencyConflictException();
            }
            return stored.response();
        }

        var response = handler.get();
        responses.put(key, new StoredResponse(command.payloadFingerprint(), response, clock.instant()));
        return response;
    }

    private record StoredCommandKey(String subjectId, String key, String method, String path) {
    }

    private record StoredResponse(String payloadFingerprint, IdempotentResponse response, Instant storedAt) {
    }
}
