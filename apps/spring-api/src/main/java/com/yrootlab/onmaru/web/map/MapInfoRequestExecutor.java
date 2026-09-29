package com.yrootlab.onmaru.web.map;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public final class MapInfoRequestExecutor {
    private static final ExecutorService DEFAULT_EXECUTOR = Executors.newVirtualThreadPerTaskExecutor();
    private final Duration timeout;
    private final ExecutorService executor;

    public MapInfoRequestExecutor(Duration timeout, ExecutorService executor) {
        this.timeout = Objects.requireNonNull(timeout);
        this.executor = Objects.requireNonNull(executor);
        if (timeout.isZero() || timeout.isNegative()) throw new IllegalArgumentException("timeout must be positive");
    }

    public MapInfoRequestExecutor(Duration timeout) {
        this(timeout, DEFAULT_EXECUTOR);
    }

    public <T> T execute(Callable<T> action) {
        Future<T> future = executor.submit(action);
        try {
            return future.get(timeout.toNanos(), TimeUnit.NANOSECONDS);
        } catch (TimeoutException exception) {
            future.cancel(true);
            throw new MapInfoRequestTimeoutException("map info request exceeded API timeout");
        } catch (InterruptedException exception) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new MapInfoRequestTimeoutException("map info request was interrupted");
        } catch (ExecutionException exception) {
            var cause = exception.getCause();
            if (cause instanceof RuntimeException runtimeException) throw runtimeException;
            if (cause instanceof Error error) throw error;
            throw new IllegalStateException("map info request failed", cause);
        }
    }
}
