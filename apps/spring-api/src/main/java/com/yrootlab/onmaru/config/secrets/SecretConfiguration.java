package com.yrootlab.onmaru.config.secrets;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
@EnableConfigurationProperties(OnMaruSecretProperties.class)
public class SecretConfiguration {

    @Bean
    SecretProvider secretProvider(OnMaruSecretProperties properties) {
        SecretProvider provider = switch (properties.getSource()) {
            case FAKE -> new FakeSecretProvider();
            case ENVIRONMENT -> new EnvironmentSecretProvider();
        };
        validateRequiredSecrets(provider, properties.getRequiredNames());
        return provider;
    }

    @Bean
    SecretRedactor secretRedactor(SecretProvider provider, OnMaruSecretProperties properties) {
        List<SecretBundle> secrets = properties.getRequiredNames().stream()
                .map(provider::get)
                .toList();
        SecretRedactor redactor = new SecretRedactor(secrets);
        RedactingMessageConverter.install(redactor);
        return redactor;
    }

    private static void validateRequiredSecrets(SecretProvider provider, List<String> requiredNames) {
        for (String name : requiredNames) {
            provider.get(name);
        }
    }
}
