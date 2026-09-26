package com.yrootlab.onmaru.web.insights;

import com.yrootlab.onmaru.config.secrets.SecretProvider;

import java.util.Objects;

final class DataLabOperationsAuthenticator {

    private static final String SECRET_NAME = "datalab.operations-token";
    private static final String BEARER_PREFIX = "Bearer ";

    private final SecretProvider secretProvider;

    DataLabOperationsAuthenticator(SecretProvider secretProvider) {
        this.secretProvider = Objects.requireNonNull(secretProvider);
    }

    boolean authenticate(String authorization) {
        if (authorization == null || !authorization.startsWith(BEARER_PREFIX)) {
            return false;
        }
        String token = authorization.substring(BEARER_PREFIX.length());
        if (token.isBlank()) {
            return false;
        }
        try {
            return secretProvider.get(SECRET_NAME).matches(token);
        } catch (IllegalStateException exception) {
            return false;
        }
    }
}
