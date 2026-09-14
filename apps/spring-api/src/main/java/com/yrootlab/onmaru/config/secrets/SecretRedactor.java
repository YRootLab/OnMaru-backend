package com.yrootlab.onmaru.config.secrets;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;

public final class SecretRedactor {

    private static final String REDACTION_TOKEN = "<REDACTED>";

    private final List<String> values;

    public SecretRedactor(Collection<SecretBundle> secrets) {
        this.values = secrets.stream()
                .flatMap(secret -> {
                    List<String> candidates = new ArrayList<>();
                    candidates.add(secret.current());
                    secret.previous().ifPresent(candidates::add);
                    return candidates.stream();
                })
                .filter(value -> !value.isBlank())
                .distinct()
                .sorted(Comparator.comparingInt(String::length).reversed())
                .toList();
    }

    public String redact(String message) {
        String redacted = message;
        for (String value : values) {
            redacted = redacted.replace(value, REDACTION_TOKEN);
        }
        return redacted;
    }
}
