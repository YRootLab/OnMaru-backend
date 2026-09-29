package com.yrootlab.onmaru.admin.auth;

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

@Component
public final class AdminPasswordHasher {

    private final PasswordEncoder delegate = new BCryptPasswordEncoder(12);

    public String encode(String rawPassword) {
        requirePassword(rawPassword);
        return delegate.encode(rawPassword);
    }

    public boolean matches(String rawPassword, String encodedPassword) {
        if (rawPassword == null || rawPassword.isBlank() || encodedPassword == null || encodedPassword.isBlank()) {
            return false;
        }
        return delegate.matches(rawPassword, encodedPassword);
    }

    private void requirePassword(String password) {
        if (password == null || password.length() < 12 || password.length() > 128) {
            throw new IllegalArgumentException("admin password must be 12 to 128 characters");
        }
    }
}
