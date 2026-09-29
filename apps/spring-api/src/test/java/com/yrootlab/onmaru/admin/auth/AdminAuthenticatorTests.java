package com.yrootlab.onmaru.admin.auth;

import com.yrootlab.onmaru.config.secrets.SecretBundle;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AdminAuthenticatorTests {

    @Test
    void authenticatesBearerAccessToken() {
        var codec = new AdminJwtTokenCodec(
                name -> new SecretBundle(name, "secret", Optional.empty()),
                "admin.jwt-signing-key",
                "onmaru-admin",
                "onmaru-admin-web",
                java.time.Duration.ofMinutes(15),
                Clock.fixed(Instant.parse("2026-09-29T00:00:00Z"), ZoneOffset.UTC));
        var expected = new AdminPrincipal(UUID.randomUUID(), "editor@onmaru.kr", AdminRole.EDITOR);

        assertThat(new AdminAuthenticator(codec).authenticate("Bearer " + codec.issue(expected)))
                .isEqualTo(expected);
    }

    @Test
    void missingOrInvalidBearerTokenRequiresAuthentication() {
        var codec = new AdminJwtTokenCodec(
                name -> new SecretBundle(name, "secret", Optional.empty()),
                "admin.jwt-signing-key",
                "onmaru-admin",
                "onmaru-admin-web",
                java.time.Duration.ofMinutes(15),
                Clock.systemUTC());

        assertThatThrownBy(() -> new AdminAuthenticator(codec).authenticate(null))
                .isInstanceOf(AdminAuthenticationException.class);
        assertThatThrownBy(() -> new AdminAuthenticator(codec).authenticate("Basic token"))
                .isInstanceOf(AdminAuthenticationException.class);
    }
}
