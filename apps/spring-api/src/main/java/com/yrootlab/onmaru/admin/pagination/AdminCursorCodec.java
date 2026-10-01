package com.yrootlab.onmaru.admin.pagination;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yrootlab.onmaru.catalog.application.pagination.AdminCursor;
import com.yrootlab.onmaru.config.secrets.SecretProvider;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.UUID;

/** HMAC-signed keyset cursor bound to one resource, page size, and filter set. */
public final class AdminCursorCodec {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

    private final SecretProvider secrets;
    private final String secretName;
    private final Clock clock;
    private static final Duration TTL = Duration.ofMinutes(15);

    public AdminCursorCodec(SecretProvider secrets, String secretName, Clock clock) {
        this.secrets = secrets;
        this.secretName = secretName;
        this.clock = clock;
        var keys = secrets.get(secretName);
        validateKey(keys.current());
        keys.previous().ifPresent(this::validateKey);
    }

    public String encode(AdminCursor cursor) {
        try {
            byte[] payload = MAPPER.writeValueAsBytes(new Payload(
                    2, cursor.resource(), cursor.limit(), cursor.filter(), cursor.timestamp().toString(), cursor.id().toString(),
                    cursor.sortGroup(),
                    clock.instant().plus(TTL).toString()));
            String encoded = ENCODER.encodeToString(payload);
            return encoded + "." + ENCODER.encodeToString(sign(encoded, secrets.get(secretName).current()));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("could not encode admin cursor", exception);
        }
    }

    public AdminCursor decode(String value, String resource, int limit, String filter) {
        try {
            String[] parts = value == null ? new String[0] : value.split("\\.", -1);
            var keyBundle = secrets.get(secretName);
            if (parts.length != 2 || !matches(parts[0], parts[1], keyBundle.current())
                    && keyBundle.previous().stream().noneMatch(previous -> matches(parts[0], parts[1], previous))) {
                throw new IllegalArgumentException("invalid cursor");
            }
            Payload payload = MAPPER.readValue(DECODER.decode(parts[0]), Payload.class);
            if ((payload.version() != 1 && payload.version() != 2) || !resource.equals(payload.resource())
                    || limit != payload.limit() || !filter.equals(payload.filter())) {
                throw new IllegalArgumentException("cursor does not match this query");
            }
            if (!Instant.parse(payload.expiresAt()).isAfter(clock.instant())) {
                throw new IllegalArgumentException("cursor has expired");
            }
            return new AdminCursor(resource, limit, filter,
                    Instant.parse(payload.timestamp()), UUID.fromString(payload.id()), payload.sortGroup());
        } catch (IllegalArgumentException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalArgumentException("invalid cursor", exception);
        }
    }

    public AdminCursor decodeOptional(String value, String resource, int limit, String filter) {
        return value == null ? null : decode(value, resource, limit, filter);
    }

    private boolean matches(String input, String signature, String key) {
        byte[] signatureBytes = DECODER.decode(signature);
        if (!ENCODER.encodeToString(signatureBytes).equals(signature)) return false;
        byte[] payloadBytes = DECODER.decode(input);
        return ENCODER.encodeToString(payloadBytes).equals(input)
                && MessageDigest.isEqual(sign(input, key), signatureBytes);
    }

    private byte[] sign(String value, String key) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            validateKey(key);
            mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return mac.doFinal(value.getBytes(StandardCharsets.US_ASCII));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("could not sign admin cursor", exception);
        }
    }

    private void validateKey(String key) {
        if (key.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalStateException("admin cursor signing key is too short");
        }
    }

    private record Payload(int version, String resource, int limit, String filter, String timestamp, String id,
                           String sortGroup, String expiresAt) {
    }
}
