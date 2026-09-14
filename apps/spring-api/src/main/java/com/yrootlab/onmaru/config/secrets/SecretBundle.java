package com.yrootlab.onmaru.config.secrets;

import java.util.Optional;

public record SecretBundle(String name, String current, Optional<String> previous) {

    public SecretBundle {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("secret name must not be blank");
        }
        if (current == null || current.isBlank()) {
            throw new IllegalArgumentException("current secret must not be blank");
        }
        previous = previous.filter(value -> !value.isBlank());
    }

    public boolean matches(String value) {
        return current.equals(value) || previous.filter(value::equals).isPresent();
    }
}
