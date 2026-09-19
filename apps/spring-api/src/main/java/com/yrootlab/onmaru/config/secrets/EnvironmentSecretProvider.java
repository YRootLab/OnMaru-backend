package com.yrootlab.onmaru.config.secrets;

import java.util.Map;
import java.util.Optional;

public final class EnvironmentSecretProvider implements SecretProvider {

    private final Map<String, String> environment;

    public EnvironmentSecretProvider() {
        this(System.getenv());
    }

    public EnvironmentSecretProvider(Map<String, String> environment) {
        this.environment = Map.copyOf(environment);
    }

    @Override
    public SecretBundle get(String name) {
        String currentKey = envKey(name, "CURRENT");
        String current = environment.get(currentKey);
        if (current == null || current.isBlank()) {
            throw new IllegalStateException(
                    "missing required secret: " + name + " (expected env " + currentKey + ")");
        }
        return new SecretBundle(name, current, Optional.ofNullable(environment.get(envKey(name, "PREVIOUS"))));
    }

    private static String envKey(String name, String suffix) {
        String normalized = name.toUpperCase()
                .replace(".", "_")
                .replace("-", "_");
        return "ONMARU_SECRET_" + normalized + "_" + suffix;
    }
}
