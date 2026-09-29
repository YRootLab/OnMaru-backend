package com.yrootlab.onmaru.admin.auth;

public record AdminLoginResult(AdminPrincipal principal, String accessToken, String refreshToken, long expiresInSeconds) {
}
