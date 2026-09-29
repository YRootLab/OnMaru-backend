package com.yrootlab.onmaru.admin.auth;

import com.yrootlab.onmaru.config.secrets.SecretBundle;
import com.yrootlab.onmaru.config.secrets.SecretProvider;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AdminJwtTokenCodecTests {

    private static final Instant NOW = Instant.parse("2026-09-29T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    private final SecretProvider secrets = name -> new SecretBundle(
            name,
            "admin-signing-secret-current",
            Optional.of("admin-signing-secret-previous"));
    private final AdminJwtTokenCodec codec = new AdminJwtTokenCodec(
            secrets,
            "admin.jwt-signing-key",
            "onmaru-admin",
            "onmaru-admin-web",
            java.time.Duration.ofMinutes(15),
            CLOCK);

    @Test
    void signsAndVerifiesAdminClaims() {
        UUID id = UUID.randomUUID();
        String token = codec.issue(new AdminPrincipal(id, "admin@onmaru.kr", AdminRole.ADMIN));

        assertThat(codec.verify("Bearer " + token))
                .isEqualTo(new AdminPrincipal(id, "admin@onmaru.kr", AdminRole.ADMIN));
    }

    @Test
    void acceptsPreviousSigningSecretDuringRotation() {
        String token = new AdminJwtTokenCodec(
                name -> new SecretBundle(name, "unused-current", Optional.of("admin-signing-secret-previous")),
                "admin.jwt-signing-key",
                "onmaru-admin",
                "onmaru-admin-web",
                java.time.Duration.ofMinutes(15),
                CLOCK)
                .issueWithSecret(new AdminPrincipal(UUID.randomUUID(), "editor@onmaru.kr", AdminRole.EDITOR),
                        "admin-signing-secret-previous");

        assertThat(codec.verify(token).role()).isEqualTo(AdminRole.EDITOR);
    }

    @Test
    void rejectsExpiredOrMalformedTokens() {
        String token = codec.issueWithLifetime(
                new AdminPrincipal(UUID.randomUUID(), "admin@onmaru.kr", AdminRole.ADMIN),
                java.time.Duration.ofSeconds(-1));

        assertThatThrownBy(() -> codec.verify(token))
                .isInstanceOf(AdminAuthenticationException.class);
        assertThatThrownBy(() -> codec.verify("Bearer not-a-jwt"))
                .isInstanceOf(AdminAuthenticationException.class);
    }
}
