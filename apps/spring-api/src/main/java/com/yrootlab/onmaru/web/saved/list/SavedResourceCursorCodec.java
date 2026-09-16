package com.yrootlab.onmaru.web.saved.list;

import com.yrootlab.onmaru.journey.saved.place.SavedResourceType;
import com.yrootlab.onmaru.web.common.cursor.CursorCodec;
import com.yrootlab.onmaru.web.common.cursor.CursorInvalidException;
import com.yrootlab.onmaru.web.common.cursor.CursorPayload;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

final class SavedResourceCursorCodec {

    private static final String SCOPE = "saved-resources:v1";
    private static final Duration TTL = Duration.ofMinutes(10);

    private final CursorCodec codec;
    private final Clock clock;

    SavedResourceCursorCodec(CursorCodec codec, Clock clock) {
        this.codec = codec;
        this.clock = clock;
    }

    String encode(Cursor cursor) {
        return codec.encode(new CursorPayload(SCOPE, Map.of(
                "memberId", cursor.memberId().toString(),
                "type", cursor.type().name(),
                "limit", cursor.limit(),
                "asOf", cursor.asOf().toString(),
                "savedAt", cursor.savedAt().toString(),
                "resourceId", cursor.resourceId()), clock.instant().plus(TTL)));
    }

    Cursor decode(String value) {
        var claims = codec.decode(value, SCOPE).claims();
        try {
            return new Cursor(
                    UUID.fromString(stringClaim(claims, "memberId")),
                    SavedResourceType.valueOf(stringClaim(claims, "type")),
                    intClaim(claims, "limit"),
                    Instant.parse(stringClaim(claims, "asOf")),
                    Instant.parse(stringClaim(claims, "savedAt")),
                    stringClaim(claims, "resourceId"));
        } catch (IllegalArgumentException exception) {
            throw new CursorInvalidException(exception);
        }
    }

    private String stringClaim(Map<String, Object> claims, String name) {
        if (claims.get(name) instanceof String value && !value.isBlank()) {
            return value;
        }
        throw new CursorInvalidException();
    }

    private int intClaim(Map<String, Object> claims, String name) {
        if (claims.get(name) instanceof Number value && value.doubleValue() == value.intValue()) {
            return value.intValue();
        }
        throw new CursorInvalidException();
    }

    Instant now() {
        return clock.instant();
    }

    record Cursor(
            UUID memberId,
            SavedResourceType type,
            int limit,
            Instant asOf,
            Instant savedAt,
            String resourceId) {
    }
}
