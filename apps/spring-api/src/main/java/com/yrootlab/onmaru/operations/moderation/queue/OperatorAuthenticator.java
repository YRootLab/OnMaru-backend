package com.yrootlab.onmaru.operations.moderation.queue;

import com.yrootlab.onmaru.config.secrets.SecretProvider;
import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

@Component
public final class OperatorAuthenticator {

    private static final String SECRET_NAME = "moderation.operator-token";
    private static final String BEARER_PREFIX = "Bearer ";
    private static final Pattern ACTOR_PATTERN = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._@-]{0,79}");

    private final SecretProvider secretProvider;

    public OperatorAuthenticator(SecretProvider secretProvider) {
        this.secretProvider = secretProvider;
    }

    public OperatorPrincipal authenticate(String authorization, String actorRef) {
        if (isBlank(authorization) || isBlank(actorRef)) {
            throw new OperatorAuthenticationRequiredException();
        }
        String normalizedActor = actorRef.trim();
        if (!authorization.startsWith(BEARER_PREFIX) || !ACTOR_PATTERN.matcher(normalizedActor).matches()) {
            throw new OperatorForbiddenException();
        }
        String token = authorization.substring(BEARER_PREFIX.length());
        if (token.isBlank() || !secretProvider.get(SECRET_NAME).matches(token)) {
            throw new OperatorForbiddenException();
        }
        return new OperatorPrincipal(normalizedActor);
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
