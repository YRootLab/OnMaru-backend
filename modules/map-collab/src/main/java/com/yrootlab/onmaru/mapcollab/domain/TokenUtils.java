package com.yrootlab.onmaru.mapcollab.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.regex.Pattern;

public final class TokenUtils {

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final Pattern TOKEN_PATTERN = Pattern.compile("^[A-Za-z0-9_-]{43,64}$");
    private static final Pattern HASH_PATTERN = Pattern.compile("^[0-9a-f]{64}$");

    private TokenUtils() {}

    public static String generateRawToken() {
        byte[] bytes = new byte[32]; // 256 bits
        SECURE_RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public static boolean isValidTokenFormat(String token) {
        return token != null && TOKEN_PATTERN.matcher(token).matches();
    }

    public static boolean isValidHashFormat(String hash) {
        return hash != null && HASH_PATTERN.matcher(hash).matches();
    }

    public static String sha256Hex(String rawToken) {
        if (rawToken == null) {
            throw new IllegalArgumentException("rawToken must not be null");
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashBytes = digest.digest(rawToken.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(64);
            for (byte b : hashBytes) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm not available", e);
        }
    }

    public static Instant calculateInviteExpiresAt(Instant issuedAt, Instant roomExpiresAt) {
        Instant defaultExpiry = issuedAt.plus(72, ChronoUnit.HOURS);
        return defaultExpiry.isBefore(roomExpiresAt) ? defaultExpiry : roomExpiresAt;
    }
}
