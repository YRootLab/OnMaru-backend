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

class AdminSessionServiceTests {

    private static final Instant NOW = Instant.parse("2026-09-29T00:00:00Z");

    @Test
    void createsAndRotatesOpaqueRefreshToken() {
        var account = account();
        var accounts = new InMemoryAdminAccountStore(account);
        var sessions = new InMemoryAdminSessionStore();
        var service = new AdminSessionService(accounts, sessions, codec(), fixedClock(), new java.security.SecureRandom());

        var first = service.create(account.principal());
        var second = service.refresh(first.refreshToken());

        assertThat(first.refreshToken()).isNotEqualTo(second.refreshToken());
        assertThat(first.accessToken()).isNotEqualTo(second.accessToken());
        assertThatThrownBy(() -> service.refresh(first.refreshToken()))
                .isInstanceOf(AdminAuthenticationException.class);
        assertThat(sessions.findByTokenHash(AdminSessionService.hash(first.refreshToken())))
                .get()
                .extracting(AdminSession::rotatedToHash)
                .isEqualTo(AdminSessionService.hash(second.refreshToken()));
    }

    @Test
    void reusedRotatedRefreshTokenInvalidatesTheWholeForwardFamily() {
        var account = account();
        var accounts = new InMemoryAdminAccountStore(account);
        var sessions = new InMemoryAdminSessionStore();
        var service = new AdminSessionService(accounts, sessions, codec(), fixedClock(), new java.security.SecureRandom());

        var first = service.create(account.principal());
        var second = service.refresh(first.refreshToken());

        assertThatThrownBy(() -> service.refresh(first.refreshToken()))
                .isInstanceOf(AdminAuthenticationException.class);
        assertThatThrownBy(() -> service.refresh(second.refreshToken()))
                .isInstanceOf(AdminAuthenticationException.class);
        assertThat(sessions.findByTokenHash(AdminSessionService.hash(second.refreshToken())))
                .get()
                .extracting(AdminSession::revokedAt)
                .isNotNull();
    }

    @Test
    void revokedOrSuspendedSessionCannotRefresh() {
        var account = account();
        var accounts = new InMemoryAdminAccountStore(account);
        var sessions = new InMemoryAdminSessionStore();
        var service = new AdminSessionService(accounts, sessions, codec(), fixedClock(), new java.security.SecureRandom());
        var created = service.create(account.principal());

        service.revoke(created.refreshToken());
        assertThatThrownBy(() -> service.refresh(created.refreshToken()))
                .isInstanceOf(AdminAuthenticationException.class);
    }

    private AdminJwtTokenCodec codec() {
        var secrets = (com.yrootlab.onmaru.config.secrets.SecretProvider) name ->
                new SecretBundle(name, "test-admin-secret", Optional.empty());
        return new AdminJwtTokenCodec(
                secrets, "admin.jwt-signing-key", "onmaru-admin", "onmaru-admin-web",
                java.time.Duration.ofMinutes(15), fixedClock());
    }

    private Clock fixedClock() {
        return Clock.fixed(NOW, ZoneOffset.UTC);
    }

    private AdminAccount account() {
        var hasher = new AdminPasswordHasher();
        return new AdminAccount(
                UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"),
                "admin@example.com", "관리자", AdminRole.ADMIN,
                hasher.encode("correct horse battery"), AdminAccountStatus.ACTIVE);
    }
}
