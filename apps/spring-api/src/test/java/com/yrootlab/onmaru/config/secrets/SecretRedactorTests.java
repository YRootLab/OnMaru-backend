package com.yrootlab.onmaru.config.secrets;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class SecretRedactorTests {

    @Test
    void redactsCurrentAndPreviousSecretValuesFromLogMessages() {
        SecretRedactor redactor = new SecretRedactor(List.of(
                new SecretBundle("gemini.api-key", "gemini-current-secret", Optional.of("gemini-previous-secret")),
                new SecretBundle("oauth.client-secret", "oauth-current-secret", Optional.empty())));

        String redacted = redactor.redact(
                "current=gemini-current-secret previous=gemini-previous-secret oauth=oauth-current-secret");

        assertThat(redacted).isEqualTo("current=<REDACTED> previous=<REDACTED> oauth=<REDACTED>");
    }
}
