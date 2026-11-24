package com.yrootlab.onmaru.integration.ai;

import com.yrootlab.onmaru.integration.ai.plan.AiPlanVersionsProperties;
import com.yrootlab.onmaru.integration.ai.plan.ConfigurableCurrentVersions;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

class ConfigurableCurrentVersionsTests {

    @Test
    void readsSourceWatermarkDynamicallyWhileOtherVersionsRemainPinned() {
        var watermark = new AtomicLong(3);
        var current = new ConfigurableCurrentVersions(AiPlanVersionsProperties.defaults(), watermark::get);

        var first = current.current();
        watermark.set(4);
        var second = current.current();

        assertThat(first.sourceRevocationSequence()).isEqualTo(3L);
        assertThat(second.sourceRevocationSequence()).isEqualTo(4L);
        assertThat(second.corpusRevision()).isEqualTo(first.corpusRevision());
        assertThat(second.configHash()).isEqualTo(first.configHash());
        assertThat(second.questionDictionaryVersion()).isEqualTo(first.questionDictionaryVersion());
    }
}
