package com.yrootlab.onmaru.web.common.idempotency;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IdempotencyServiceTests {

    private final IdempotencyService service = new IdempotencyService(
            new InMemoryIdempotencyStore(),
            Clock.fixed(Instant.parse("2026-09-14T00:00:00Z"), ZoneOffset.UTC));

    @Test
    void replaysStoredResponseForSameKeyAndPayloadFingerprint() {
        var command = command("POST", "/api/v1/explorations", "member-1", "hash-a");

        var first = service.execute(command, () -> IdempotentResponse.created(
                "/api/v1/explorations/exploration-1",
                Map.of("explorationId", "exploration-1")));
        var second = service.execute(command, () -> IdempotentResponse.created(
                "/api/v1/explorations/exploration-2",
                Map.of("explorationId", "exploration-2")));

        assertThat(first.status()).isEqualTo(201);
        assertThat(second.status()).isEqualTo(201);
        assertThat(second.body()).isEqualTo(Map.of("explorationId", "exploration-1"));
        assertThat(second.headers()).containsEntry("Location", "/api/v1/explorations/exploration-1");
    }

    @Test
    void rejectsSameKeyWithDifferentPayloadFingerprint() {
        service.execute(command("POST", "/api/v1/explorations", "member-1", "hash-a"),
                () -> IdempotentResponse.accepted(Map.of("runId", "run-1")));

        assertThatThrownBy(() -> service.execute(
                command("POST", "/api/v1/explorations", "member-1", "hash-b"),
                () -> IdempotentResponse.accepted(Map.of("runId", "run-2"))))
                .isInstanceOf(IdempotencyConflictException.class);
    }

    @Test
    void runsCommandOnceWhenSameKeyArrivesConcurrently() throws Exception {
        var executions = new AtomicInteger();
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        var command = command("POST", "/api/v1/explorations", "member-1", "hash-a");

        var first = pool.submit(() -> executeAfterStart(command, executions, ready, start));
        var second = pool.submit(() -> executeAfterStart(command, executions, ready, start));

        assertThat(ready.await(1, TimeUnit.SECONDS)).isTrue();
        start.countDown();

        assertThat(first.get(1, TimeUnit.SECONDS).body()).isEqualTo(Map.of("runId", "run-1"));
        assertThat(second.get(1, TimeUnit.SECONDS).body()).isEqualTo(Map.of("runId", "run-1"));
        assertThat(executions).hasValue(1);
        pool.shutdownNow();
    }

    private IdempotentResponse executeAfterStart(
            IdempotencyCommand command,
            AtomicInteger executions,
            CountDownLatch ready,
            CountDownLatch start) throws Exception {
        ready.countDown();
        start.await(1, TimeUnit.SECONDS);
        return service.execute(command, () -> {
            executions.incrementAndGet();
            return IdempotentResponse.accepted(Map.of("runId", "run-1"));
        });
    }

    private IdempotencyCommand command(String method, String path, String subjectId, String payloadFingerprint) {
        return new IdempotencyCommand(
                UUID.fromString("00000000-0000-0000-0000-000000000001"),
                subjectId,
                method,
                path,
                payloadFingerprint);
    }
}
