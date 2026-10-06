package com.yrootlab.onmaru.admin.pipeline;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class AdminPipelineFailurePageTests {
    @Test
    void pageKeepsFailuresIsolatedToOneRunAndReportsStableTotal() {
        UUID runId = UUID.fromString("00000000-0000-0000-0000-000000000668");
        var item = new AdminPipelineFailure(
                UUID.fromString("00000000-0000-0000-0000-000000000669"), runId,
                Instant.parse("2026-10-06T04:02:11Z"), "detailCommon2", "126513",
                "UPSTREAM_TIMEOUT", "TourAPI request timed out", true);

        var page = new AdminPipelineFailurePage(List.of(item), 3, false, null);

        assertThat(page.schemaVersion()).isEqualTo("1.0");
        assertThat(page.items()).extracting(AdminPipelineFailure::runId).containsOnly(runId);
        assertThat(page.totalCount()).isEqualTo(3);
        assertThat(page.nextCursor()).isNull();
    }
}
