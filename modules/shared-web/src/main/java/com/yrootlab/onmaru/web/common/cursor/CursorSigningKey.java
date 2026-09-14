package com.yrootlab.onmaru.web.common.cursor;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

public final class CursorSigningKey {

    private final byte[] value;

    private CursorSigningKey(byte[] value) {
        if (value.length < 32) {
            throw new IllegalArgumentException("cursor signing key must be at least 32 bytes");
        }
        this.value = Arrays.copyOf(value, value.length);
    }

    public static CursorSigningKey fromUtf8(String value) {
        if (value == null) {
            throw new IllegalArgumentException("cursor signing key must not be null");
        }
        return new CursorSigningKey(value.getBytes(StandardCharsets.UTF_8));
    }

    byte[] bytes() {
        return Arrays.copyOf(value, value.length);
    }
}
