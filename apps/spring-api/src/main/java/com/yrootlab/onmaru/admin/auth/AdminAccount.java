package com.yrootlab.onmaru.admin.auth;

import java.util.UUID;

public record AdminAccount(
        UUID id,
        String email,
        String nickname,
        AdminRole role,
        String passwordHash,
        AdminAccountStatus status) {

    public AdminAccount {
        if (id == null || email == null || email.isBlank() || nickname == null || nickname.isBlank()
                || role == null || passwordHash == null || passwordHash.isBlank() || status == null) {
            throw new IllegalArgumentException("admin account is invalid");
        }
        email = email.trim().toLowerCase(java.util.Locale.ROOT);
    }

    public AdminAccount withStatus(AdminAccountStatus nextStatus) {
        return new AdminAccount(id, email, nickname, role, passwordHash, nextStatus);
    }

    public AdminPrincipal principal() {
        return new AdminPrincipal(id, email, role);
    }
}
