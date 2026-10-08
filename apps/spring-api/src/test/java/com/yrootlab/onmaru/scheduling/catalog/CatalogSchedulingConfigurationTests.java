package com.yrootlab.onmaru.scheduling.catalog;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.scheduling.config.TaskManagementConfigUtils;

import static org.assertj.core.api.Assertions.assertThat;

class CatalogSchedulingConfigurationTests {
    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withUserConfiguration(CatalogSchedulingConfiguration.class);

    @Test void ordinaryApplicationRegistersScheduledTasks() {
        context.run(result -> assertThat(result.containsBean(
                TaskManagementConfigUtils.SCHEDULED_ANNOTATION_PROCESSOR_BEAN_NAME)).isTrue());
    }

    @Test void oneShotDiscoveryOperatorDoesNotStartScheduledTasks() {
        context.withPropertyValues("onmaru.discovery.operator.action=revoke")
                .run(result -> assertThat(result.containsBean(
                        TaskManagementConfigUtils.SCHEDULED_ANNOTATION_PROCESSOR_BEAN_NAME)).isFalse());
    }
}
