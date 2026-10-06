package com.yrootlab.onmaru.admin.auth;

import java.time.Instant;

public record AdminTokenValidity(AdminAccountStatus status, Instant tokensValidAfter) {

    public AdminTokenValidity {
        if (status == null || tokensValidAfter == null) {
            throw new IllegalArgumentException("admin token validity is required");
        }
    }
}
