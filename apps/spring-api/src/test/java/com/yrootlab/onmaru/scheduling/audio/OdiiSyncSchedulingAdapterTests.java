package com.yrootlab.onmaru.scheduling.audio;

import org.junit.jupiter.api.Test;
import org.springframework.scheduling.annotation.Scheduled;

import static org.assertj.core.api.Assertions.assertThat;

class OdiiSyncSchedulingAdapterTests {

    @Test
    void runsTheNightlyFullCollectionAtThreeAmKoreaTime() throws Exception {
        Scheduled scheduled = OdiiSyncSchedulingAdapter.class
                .getMethod("scheduledSync")
                .getAnnotation(Scheduled.class);

        assertThat(scheduled.cron()).isEqualTo("${onmaru.odii.sync.cron:0 0 3 * * *}");
        assertThat(scheduled.zone()).isEqualTo("Asia/Seoul");
    }
}
