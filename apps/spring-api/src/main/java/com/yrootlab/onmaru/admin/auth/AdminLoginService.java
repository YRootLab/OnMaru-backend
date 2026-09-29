package com.yrootlab.onmaru.admin.auth;

import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Locale;

@Service
public final class AdminLoginService {

    public static final long ACCESS_TOKEN_TTL_SECONDS = 15 * 60L;

    private final AdminAccountStore accounts;
    private final AdminPasswordHasher passwordHasher;
    private final AdminSessionService sessionService;

    @Autowired
    public AdminLoginService(
            AdminAccountStore accounts,
            AdminPasswordHasher passwordHasher,
            AdminSessionService sessionService) {
        this.accounts = accounts;
        this.passwordHasher = passwordHasher;
        this.sessionService = sessionService;
    }

    public AdminLoginService(
            AdminAccountStore accounts,
            AdminPasswordHasher passwordHasher,
            AdminJwtTokenCodec tokenCodec) {
        this(accounts, passwordHasher, new AdminSessionService(
                accounts, new InMemoryAdminSessionStore(), tokenCodec, java.time.Clock.systemUTC()));
    }

    public AdminLoginResult login(String email, String password) {
        if (email == null || email.isBlank() || password == null || password.isBlank()) {
            throw new AdminAuthenticationException();
        }
        String normalizedEmail = email.trim().toLowerCase(Locale.ROOT);
        AdminAccount account = accounts.findByEmail(normalizedEmail).orElseThrow(AdminAuthenticationException::new);
        if (account.status() != AdminAccountStatus.ACTIVE || !passwordHasher.matches(password, account.passwordHash())) {
            throw new AdminAuthenticationException();
        }
        accounts.recordLogin(account);
        var session = sessionService.create(account.principal());
        return new AdminLoginResult(account.principal(), session.accessToken(), session.refreshToken(), session.expiresInSeconds());
    }
}
