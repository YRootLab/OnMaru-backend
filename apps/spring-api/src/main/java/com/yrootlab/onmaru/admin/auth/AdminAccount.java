package com.yrootlab.onmaru.admin.auth;

import java.util.UUID;
import java.time.Instant;

public record AdminAccount(
        UUID id,
        String email,
        String nickname,
        AdminRole role,
        String passwordHash,
        AdminAccountStatus status,
        Instant tokensValidAfter) {

    public AdminAccount {
        if (id == null || email == null || email.isBlank() || nickname == null || nickname.isBlank()
                || role == null || passwordHash == null || passwordHash.isBlank() || status == null
                || tokensValidAfter == null) {
            throw new IllegalArgumentException("admin account is invalid");
        }
        email = email.trim().toLowerCase(java.util.Locale.ROOT);
    }

    public AdminAccount(
            UUID id,
            String email,
            String nickname,
            AdminRole role,
            String passwordHash,
            AdminAccountStatus status) {
        this(id, email, nickname, role, passwordHash, status, Instant.MIN);
    }

    public AdminAccount withStatus(AdminAccountStatus nextStatus) {
        return new AdminAccount(id, email, nickname, role, passwordHash, nextStatus, tokensValidAfter);
    }

    public AdminPrincipal principal() {
        return new AdminPrincipal(id, email, role);
    }
}
