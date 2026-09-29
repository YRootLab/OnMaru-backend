package com.yrootlab.onmaru.admin.auth;

import java.util.UUID;

public record AdminPrincipal(UUID id, String email, AdminRole role) {

    public AdminPrincipal {
        if (id == null || email == null || email.isBlank() || role == null) {
            throw new IllegalArgumentException("admin principal is invalid");
        }
        email = email.trim().toLowerCase(java.util.Locale.ROOT);
    }
}
