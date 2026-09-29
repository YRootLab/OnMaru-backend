package com.yrootlab.onmaru.web.map;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MapInfoRequestExecutorTests {

    @Test
    void cancelsAQueryThatExceedsTheApiBudget() {
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var runner = new MapInfoRequestExecutor(Duration.ofMillis(20), executor);

            assertThatThrownBy(() -> runner.execute(() -> {
                Thread.sleep(250);
                return "late";
            }))
                    .isInstanceOf(MapInfoRequestTimeoutException.class);
        }
    }

    @Test
    void returnsAQueryThatFinishesWithinTheApiBudget() {
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var runner = new MapInfoRequestExecutor(Duration.ofSeconds(1), executor);

            assertThat(runner.execute(() -> "ready")).isEqualTo("ready");
        }
    }
}
