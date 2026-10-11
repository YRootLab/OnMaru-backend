package com.yrootlab.onmaru.scheduling.catalog.selected;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class SelectedDiscoverySchedulingAdapterTests {
    @Test void weeklyDueIsMondayThreeAmSeoulAndStartupCatchesOnlyLatestSlot() {
        assertThat(SelectedDiscoverySchedulingAdapter.latestDue(Instant.parse("2026-10-08T14:00:00Z")))
                .isEqualTo(Instant.parse("2026-10-04T18:00:00Z"));
        assertThat(SelectedDiscoverySchedulingAdapter.latestDue(Instant.parse("2026-10-11T17:59:59Z")))
                .isEqualTo(Instant.parse("2026-10-04T18:00:00Z"));
        assertThat(SelectedDiscoverySchedulingAdapter.latestDue(Instant.parse("2026-10-11T18:00:00Z")))
                .isEqualTo(Instant.parse("2026-10-11T18:00:00Z"));
    }

    @Test void weeklySchedulerIsOffByDefaultAndDoesNotTouchLegacyDailyScheduler() {
        new ApplicationContextRunner().withUserConfiguration(SelectedDiscoverySchedulingAdapter.class)
                .run(context -> assertThat(context).doesNotHaveBean(SelectedDiscoverySchedulingAdapter.class));
        new ApplicationContextRunner().withUserConfiguration(SelectedDiscoverySchedulingAdapter.class)
                .withPropertyValues("onmaru.discovery.enabled=true", "onmaru.discovery.operator.action=preview")
                .run(context -> assertThat(context).doesNotHaveBean(SelectedDiscoverySchedulingAdapter.class));
    }
}
