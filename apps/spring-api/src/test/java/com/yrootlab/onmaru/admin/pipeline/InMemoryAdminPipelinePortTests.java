package com.yrootlab.onmaru.admin.pipeline;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
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
        assertThat(port.status("kto-korean-tour").failureCount()).isZero();
        assertThat(port.status("kto-korean-tour").lastRun().failureCount()).isZero();
    }

    @Test
    void exposesLastRunDurationWithoutMixingCumulativeFailedRuns() {
        UUID runId = UUID.fromString("00000000-0000-0000-0000-000000000668");
        var lastRun = new AdminPipelineRun(
                runId, "kto-korean-tour", "ALL", "SUCCEEDED", null,
                Instant.parse("2026-10-06T04:00:00Z"), Instant.parse("2026-10-06T04:05:32Z"), 3);
        var status = AdminPipelineStatus.from("kto-korean-tour", "SUCCEEDED",
                Instant.parse("2026-10-06T04:05:32Z"), 9, lastRun);

        assertThat(status.failureCount()).isEqualTo(3);
        assertThat(status.cumulativeFailureRunCount()).isEqualTo(9);
        assertThat(status.lastRun().durationSeconds()).isEqualTo(332);
    }

    @Test
    void missingRunKeepsNullableDetailedContract() {
        var status = AdminPipelineStatus.from("kto-korean-tour", "MISSING", null, 0, null);

        assertThat(status.lastRun()).isNull();
        assertThat(status.contentStats()).isNull();
        assertThat(status.apiUsage()).isNull();
        assertThat(status.failureCount()).isZero();
    }
}
