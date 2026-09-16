package com.yrootlab.onmaru.config.secrets;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
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
        if (value == null) {
            return false;
        }
        byte[] candidateDigest = digest(value);
        boolean currentMatches = MessageDigest.isEqual(candidateDigest, digest(current));
        boolean previousMatches = previous
                .map(previousValue -> MessageDigest.isEqual(candidateDigest, digest(previousValue)))
                .orElse(false);
        return currentMatches | previousMatches;
    }

    private byte[] digest(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
