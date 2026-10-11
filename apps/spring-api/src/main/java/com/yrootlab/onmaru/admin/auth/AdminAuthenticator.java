package com.yrootlab.onmaru.admin.auth;

import org.springframework.stereotype.Component;

@Component
public final class AdminAuthenticator {

    private final AdminJwtTokenCodec tokenCodec;

    public AdminAuthenticator(AdminJwtTokenCodec tokenCodec) {
        this.tokenCodec = tokenCodec;
    }

    public AdminPrincipal authenticate(String authorization) {
        return authenticateToken(authorization).principal();
    }

    public AdminAccessToken authenticateToken(String authorization) {
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            throw new AdminAuthenticationException();
        }
        return tokenCodec.verifyToken(authorization);
    }
}
