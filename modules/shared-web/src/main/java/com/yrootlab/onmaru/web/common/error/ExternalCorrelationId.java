package com.yrootlab.onmaru.web.common.error;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.HexFormat;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

public final class ExternalCorrelationId {

    private static final String ALGORITHM = "HmacSHA256";
    private static final byte[] PROCESS_KEY = processKey();
    private static final HexFormat HEX = HexFormat.of();

    private ExternalCorrelationId() {
    }

    public static String opaque(String namespace, String externalValue) {
        return namespace + "-" + opaqueHex(namespace, externalValue);
    }

    public static String opaqueHex(String namespace, String externalValue) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(PROCESS_KEY, ALGORITHM));
            mac.update(namespace.getBytes(StandardCharsets.UTF_8));
            mac.update((byte) 0);
            byte[] digest = mac.doFinal(externalValue.getBytes(StandardCharsets.UTF_8));
            return HEX.formatHex(digest, 0, 16);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("HMAC-SHA256 is unavailable", exception);
        }
    }

    private static byte[] processKey() {
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        return key;
    }
}
