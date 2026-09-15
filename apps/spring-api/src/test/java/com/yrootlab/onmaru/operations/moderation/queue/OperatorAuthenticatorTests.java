package com.yrootlab.onmaru.operations.moderation.queue;

import com.yrootlab.onmaru.config.secrets.SecretBundle;
import com.yrootlab.onmaru.config.secrets.SecretProvider;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OperatorAuthenticatorTests {

    private final SecretProvider secretProvider = name -> new SecretBundle(
            name,
            "current-token",
            Optional.of("previous-token"));
    private final OperatorAuthenticator authenticator = new OperatorAuthenticator(secretProvider);

    @Test
    void acceptsCurrentAndPreviousRotationTokensWithActorHeader() {
        assertThat(authenticator.authenticate("Bearer current-token", "operator-1").actorRef())
                .isEqualTo("operator-1");
        assertThat(authenticator.authenticate("Bearer previous-token", "operator.2@example.com").actorRef())
                .isEqualTo("operator.2@example.com");
    }

    @Test
    void missingCredentialOrActorRequiresAuthentication() {
        assertThatThrownBy(() -> authenticator.authenticate(null, "operator-1"))
                .isInstanceOf(OperatorAuthenticationRequiredException.class);
        assertThatThrownBy(() -> authenticator.authenticate("Bearer current-token", null))
                .isInstanceOf(OperatorAuthenticationRequiredException.class);
        assertThatThrownBy(() -> authenticator.authenticate("", "operator-1"))
                .isInstanceOf(OperatorAuthenticationRequiredException.class);
    }

    @Test
    void malformedOrWrongCredentialAndInvalidActorAreForbidden() {
        assertThatThrownBy(() -> authenticator.authenticate("Basic current-token", "operator-1"))
                .isInstanceOf(OperatorForbiddenException.class);
        assertThatThrownBy(() -> authenticator.authenticate("Bearer wrong-token", "operator-1"))
                .isInstanceOf(OperatorForbiddenException.class);
        assertThatThrownBy(() -> authenticator.authenticate("Bearer current-token", "operator 1"))
                .isInstanceOf(OperatorForbiddenException.class);
    }
}
