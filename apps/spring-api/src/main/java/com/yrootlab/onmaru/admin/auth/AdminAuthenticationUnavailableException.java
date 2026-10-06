package com.yrootlab.onmaru.admin.auth;

public final class AdminAuthenticationUnavailableException extends RuntimeException {

    public AdminAuthenticationUnavailableException(Throwable cause) {
        super("Admin authentication storage is unavailable", cause);
    }
}
