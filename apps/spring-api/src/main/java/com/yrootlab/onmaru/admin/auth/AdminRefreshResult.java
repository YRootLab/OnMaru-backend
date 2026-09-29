package com.yrootlab.onmaru.admin.auth;

public record AdminRefreshResult(String accessToken, String refreshToken, long expiresInSeconds, AdminPrincipal principal) {
}
