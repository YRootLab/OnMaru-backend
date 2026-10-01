package com.yrootlab.onmaru.admin.auth;

public final class AdminAuthenticationException extends RuntimeException {

    public AdminAuthenticationException() {
        super("Admin authentication failed");
    }

    public AdminAuthenticationException(String message) {
        super(message);
    }
}
