package com.yrootlab.onmaru.admin.pipeline;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class InMemoryAdminPipelinePortTests {
    @Test
    void exposesSafeMissingStatusAndAcceptedRunInNonProduction() {
        var port = new InMemoryAdminPipelinePort();

        assertThat(port.status("kto-korean-tour").status()).isEqualTo("MISSING");
        assertThat(port.run("kto-korean-tour").status()).isEqualTo("QUEUED");
    }

    @Test
    void acceptsProductionRunWithoutExecutingOnTheRequestThreadAndTracksStateTransitions() {
        var scheduled = new AtomicReference<Runnable>();
        var port = new TourApiAdminPipelinePort(
                dataset -> new AdminPipelineStatus(dataset, "MISSING", null, 0),
                () -> { },
                scheduled::set,
                Clock.fixed(Instant.parse("2026-09-29T00:00:00Z"), ZoneOffset.UTC)
        );

        var accepted = port.run("kto-korean-tour");

        assertThat(accepted.status()).isEqualTo("QUEUED");
        assertThat(port.status("kto-korean-tour").status()).isEqualTo("QUEUED");
        assertThat(scheduled.get()).isNotNull();
        scheduled.get().run();
        assertThat(port.status("kto-korean-tour").status()).isEqualTo("SUCCEEDED");
        assertThat(port.status("kto-korean-tour").lastSuccessAt())
                .isEqualTo(Instant.parse("2026-09-29T00:00:00Z"));
    }

    @Test
    void coalescesConcurrentRequestsWhileAFullSyncIsActive() {
        var scheduled = new AtomicReference<Runnable>();
        var port = new TourApiAdminPipelinePort(
                dataset -> new AdminPipelineStatus(dataset, "MISSING", null, 0),
                () -> { },
                scheduled::set,
                Clock.systemUTC()
        );

        var first = port.run("kto-korean-tour");
        var second = port.run("kto-korean-tour");

        assertThat(second.runId()).isEqualTo(first.runId());
        assertThat(second.status()).isEqualTo("QUEUED");
    }

    @Test
    void recordsFailedTransitionWhenTheBackgroundSyncFails() {
        var scheduled = new AtomicReference<Runnable>();
        var port = new TourApiAdminPipelinePort(
                dataset -> new AdminPipelineStatus(dataset, "MISSING", null, 0),
                () -> { throw new IllegalStateException("upstream unavailable"); },
                scheduled::set,
                Clock.systemUTC()
        );

        port.run("kto-korean-tour");
        scheduled.get().run();

        assertThat(port.status("kto-korean-tour").status()).isEqualTo("FAILED");
        assertThat(port.status("kto-korean-tour").failureCount()).isEqualTo(1);
    }
}
