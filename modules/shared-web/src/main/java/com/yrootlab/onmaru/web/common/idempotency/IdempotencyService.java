package com.yrootlab.onmaru.web.common.idempotency;

import java.time.Clock;
import java.util.function.Supplier;

public final class IdempotencyService {

    private final IdempotencyStorePort storePort;
    private final Clock clock;

    public IdempotencyService(IdempotencyStorePort storePort, Clock clock) {
        this.storePort = storePort;
        this.clock = clock;
    }

    public IdempotentResponse execute(IdempotencyCommand command, Supplier<IdempotentResponse> handler) {
        return storePort.execute(command, clock, handler);
    }
}
