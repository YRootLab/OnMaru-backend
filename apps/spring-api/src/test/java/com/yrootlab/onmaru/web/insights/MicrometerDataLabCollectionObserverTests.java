package com.yrootlab.onmaru.web.insights;

import com.yrootlab.onmaru.insights.ingestion.DataLabCollectionOutcome;
import com.yrootlab.onmaru.insights.ingestion.DataLabCollectionReason;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MicrometerDataLabCollectionObserverTests {

    @Test
    void recordsOnlyBoundedOutcomeAndReasonTags() {
        var registry = new SimpleMeterRegistry();
        var observer = new MicrometerDataLabCollectionObserver(registry);

        observer.record(DataLabCollectionOutcome.SKIPPED, DataLabCollectionReason.PENDING_MAPPING);
        observer.record(DataLabCollectionOutcome.PUBLISHED, null);

        assertThat(registry.get("onmaru.datalab.collection")
                .tags("outcome", "skipped", "reason", "pending_mapping")
                .counter().count()).isEqualTo(1.0);
        assertThat(registry.get("onmaru.datalab.collection")
                .tags("outcome", "published", "reason", "none")
                .counter().count()).isEqualTo(1.0);
        assertThat(registry.getMeters()).allSatisfy(meter ->
                assertThat(meter.getId().getTags())
                        .extracting(tag -> tag.getKey())
                        .containsExactly("outcome", "reason"));
    }
}
