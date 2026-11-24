package com.yrootlab.onmaru.internal.ai.revocation;

import com.yrootlab.onmaru.persistence.catalog.JdbcAiSourceRevocationStore;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class AiSourceRevocationConfigurationTests {
    @Test
    void registersStoreWhenDataSourceIsAutoConfiguredAfterUserConfiguration() {
        new ApplicationContextRunner()
                .withUserConfiguration(AiSourceRevocationConfiguration.class)
                .withConfiguration(AutoConfigurations.of(DataSourceAutoConfiguration.class))
                .withPropertyValues("spring.datasource.url=jdbc:postgresql://127.0.0.1:1/not-used",
                        "spring.datasource.username=synthetic-smoke")
                .run(context -> assertThat(context).hasSingleBean(JdbcAiSourceRevocationStore.class));
    }

    @Test
    void registersStoreWithProductionDataSourceDeclaredLater() throws ClassNotFoundException {
        new ApplicationContextRunner()
                .withInitializer(context -> context.getEnvironment().setActiveProfiles("production"))
                .withUserConfiguration(AiSourceRevocationConfiguration.class,
                        Class.forName("com.yrootlab.onmaru.persistence.audio.ProductionDataSourceConfiguration"))
                .withPropertyValues("spring.datasource.url=jdbc:postgresql://127.0.0.1:1/not-used",
                        "spring.datasource.username=synthetic-smoke",
                        "spring.datasource.password=synthetic-smoke")
                .run(context -> {
                    assertThat(context).hasSingleBean(javax.sql.DataSource.class);
                    assertThat(context).hasSingleBean(JdbcAiSourceRevocationStore.class);
                });
    }

    @Test
    void keepsStartupAvailableWithoutDatabase() {
        new ApplicationContextRunner()
                .withUserConfiguration(AiSourceRevocationConfiguration.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBeanProvider(JdbcAiSourceRevocationStore.class).getIfAvailable())
                            .isNull();
                });
    }
}
