package com.yrootlab.onmaru.admin.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Locale;

public final class AdminSessionService {

    public static final long ACCESS_TOKEN_TTL_SECONDS = 15 * 60L;
    private static final Duration REFRESH_TOKEN_TTL = Duration.ofDays(14);
    private static final Base64.Encoder TOKEN_ENCODER = Base64.getUrlEncoder().withoutPadding();

    private final AdminAccountStore accounts;
    private final AdminSessionStore sessions;
    private final AdminJwtTokenCodec tokenCodec;
    private final Clock clock;
    private final SecureRandom random;

    public AdminSessionService(
            AdminAccountStore accounts,
            AdminSessionStore sessions,
            AdminJwtTokenCodec tokenCodec,
            Clock clock) {
        this(accounts, sessions, tokenCodec, clock, new SecureRandom());
    }

    AdminSessionService(
            AdminAccountStore accounts,
            AdminSessionStore sessions,
            AdminJwtTokenCodec tokenCodec,
            Clock clock,
            SecureRandom random) {
        this.accounts = accounts;
        this.sessions = sessions;
        this.tokenCodec = tokenCodec;
        this.clock = clock;
        this.random = random;
    }

    public AdminRefreshResult create(AdminPrincipal principal) {
        String refreshToken = newToken();
        Instant now = clock.instant();
        sessions.save(new AdminSession(hash(refreshToken), principal.id(), now, now, now.plus(REFRESH_TOKEN_TTL), null, null));
        return result(principal, refreshToken);
    }

    public AdminRefreshResult refresh(String refreshToken) {
        if (refreshToken == null || refreshToken.isBlank()) {
            throw new AdminAuthenticationException();
        }
        Instant now = clock.instant();
        AdminSession current = sessions.findByTokenHash(hash(refreshToken))
                .filter(session -> session.activeAt(now))
                .orElseThrow(AdminAuthenticationException::new);
        AdminAccount account = accounts.findById(current.adminId())
                .filter(candidate -> candidate.status() == AdminAccountStatus.ACTIVE)
                .orElseThrow(AdminAuthenticationException::new);
        String replacementToken = newToken();
        AdminSession replacement = new AdminSession(
                hash(replacementToken), current.adminId(), now, now, now.plus(REFRESH_TOKEN_TTL), null, null);
        sessions.replace(current, current.rotatedTo(replacement.tokenHash(), now));
        return result(account.principal(), replacementToken);
    }

    public void revoke(String refreshToken) {
        if (refreshToken == null || refreshToken.isBlank()) {
            return;
        }
        sessions.revoke(hash(refreshToken), clock.instant());
    }

    private AdminRefreshResult result(AdminPrincipal principal, String refreshToken) {
        return new AdminRefreshResult(
                tokenCodec.issue(principal), refreshToken, ACCESS_TOKEN_TTL_SECONDS, principal);
    }

    private String newToken() {
        byte[] bytes = new byte[48];
        random.nextBytes(bytes);
        return TOKEN_ENCODER.encodeToString(bytes);
    }

    static String hash(String token) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(64);
            for (byte value : digest) {
                result.append(String.format(Locale.ROOT, "%02x", value));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required", exception);
        }
    }
}
