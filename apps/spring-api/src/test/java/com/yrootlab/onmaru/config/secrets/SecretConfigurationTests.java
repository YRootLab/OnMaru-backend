package com.yrootlab.onmaru.config.secrets;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class SecretConfigurationTests {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(SecretConfiguration.class);

    @Test
    void environmentProviderFailsStartupWhenRequiredSecretIsMissing() {
        contextRunner
                .withBean(EnvironmentSecretProvider.class, () -> new EnvironmentSecretProvider(java.util.Map.of()))
                .withPropertyValues(
                        "onmaru.secrets.source=environment",
                        "onmaru.secrets.required-names=tourapi.service-key")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void fakeProviderSuppliesCurrentAndPreviousValuesForRotationOverlap() {
        contextRunner
                .withPropertyValues(
                        "onmaru.secrets.source=fake",
                        "onmaru.secrets.required-names=tourapi.service-key")
                .run(context -> {
                    assertThat(context).hasNotFailed();

                    SecretProvider provider = context.getBean(SecretProvider.class);
                    SecretBundle bundle = provider.get("tourapi.service-key");

                    assertThat(bundle.current()).isEqualTo("fake-tourapi-service-key-current");
                    assertThat(bundle.previous()).contains("fake-tourapi-service-key-previous");
                    assertThat(bundle.matches("fake-tourapi-service-key-current")).isTrue();
                    assertThat(bundle.matches("fake-tourapi-service-key-previous")).isTrue();
                    assertThat(bundle.matches("other-value")).isFalse();
                });
    }

    @Test
    void missingModerationOperatorTokenFailsClosed() {
        contextRunner
                .withPropertyValues(
                        "onmaru.secrets.source=environment",
                        "onmaru.secrets.required-names=moderation.operator-token")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void moderationOperatorTokenIsRequiredByDefault() {
        assertThat(new OnMaruSecretProperties().getRequiredNames())
                .contains("moderation.operator-token");
    }

    @Test
    void dataLabOperationsTokenIsRequiredByDefault() {
        assertThat(new OnMaruSecretProperties().getRequiredNames())
                .contains("datalab.operations-token");
    }
}
