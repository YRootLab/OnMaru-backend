package com.yrootlab.onmaru.web.common.idempotency;

import java.time.Clock;
import java.util.function.Supplier;

public interface IdempotencyStorePort {

    IdempotentResponse execute(IdempotencyCommand command, Clock clock, Supplier<IdempotentResponse> handler);
}
