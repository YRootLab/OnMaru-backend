package com.yrootlab.onmaru.admin.auth;

import java.time.Instant;

public record AdminAccessToken(AdminPrincipal principal, String jti, Instant expiresAt) {
}
