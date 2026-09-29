package com.yrootlab.onmaru.admin.auth;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AdminLoginServiceTests {

    private final AdminAccount account = new AdminAccount(
            UUID.randomUUID(),
            "admin@onmaru.kr",
            "온마루지기",
            AdminRole.ADMIN,
            new AdminPasswordHasher().encode("correct horse battery staple"),
            AdminAccountStatus.ACTIVE);
    private final InMemoryAdminAccountStore store = new InMemoryAdminAccountStore(account);
    private final AdminJwtTokenCodec tokenCodec = new AdminJwtTokenCodec(
            name -> new com.yrootlab.onmaru.config.secrets.SecretBundle(
                    name, "secret", Optional.empty()),
            "admin.jwt-signing-key",
            "onmaru-admin",
            "onmaru-admin-web",
            java.time.Duration.ofMinutes(15),
            Clock.fixed(Instant.parse("2026-09-29T00:00:00Z"), ZoneOffset.UTC));

    @Test
    void authenticatesActiveAccountAndIssuesAccessToken() {
        AdminLoginResult result = new AdminLoginService(store, new AdminPasswordHasher(), tokenCodec)
                .login("ADMIN@ONMARU.KR", "correct horse battery staple");

        assertThat(result.principal()).isEqualTo(new AdminPrincipal(
                account.id(), account.email(), AdminRole.ADMIN));
        assertThat(tokenCodec.verify(result.accessToken())).isEqualTo(result.principal());
    }

    @Test
    void rejectsUnknownInactiveOrWrongPasswordWithSameAuthenticationError() {
        var service = new AdminLoginService(store, new AdminPasswordHasher(), tokenCodec);

        assertThatThrownBy(() -> service.login("missing@onmaru.kr", "correct horse battery staple"))
                .isInstanceOf(AdminAuthenticationException.class);
        assertThatThrownBy(() -> service.login("admin@onmaru.kr", "wrong password"))
                .isInstanceOf(AdminAuthenticationException.class);

        store.replace(account.withStatus(AdminAccountStatus.SUSPENDED));
        assertThatThrownBy(() -> service.login("admin@onmaru.kr", "correct horse battery staple"))
                .isInstanceOf(AdminAuthenticationException.class);
    }
}
