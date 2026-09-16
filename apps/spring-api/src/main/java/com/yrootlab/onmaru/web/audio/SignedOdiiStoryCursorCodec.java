package com.yrootlab.onmaru.web.audio;

import com.yrootlab.onmaru.audio.query.OdiiCursorExpiredException;
import com.yrootlab.onmaru.audio.query.OdiiCursorInvalidException;
import com.yrootlab.onmaru.audio.query.OdiiStoryCursor;
import com.yrootlab.onmaru.audio.query.OdiiStoryCursorCodec;
import com.yrootlab.onmaru.web.common.cursor.CursorCodec;
import com.yrootlab.onmaru.web.common.cursor.CursorExpiredException;
import com.yrootlab.onmaru.web.common.cursor.CursorInvalidException;
import com.yrootlab.onmaru.web.common.cursor.CursorPayload;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

final class SignedOdiiStoryCursorCodec implements OdiiStoryCursorCodec {

    private static final String SCOPE = "odii-stories:v1";
    private static final Duration TTL = Duration.ofDays(30);

    private final CursorCodec cursorCodec;
    private final Clock clock;

    SignedOdiiStoryCursorCodec(CursorCodec cursorCodec, Clock clock) {
        this.cursorCodec = cursorCodec;
        this.clock = clock;
    }

    @Override
    public String encode(OdiiStoryCursor cursor) {
        return cursorCodec.encode(new CursorPayload(
                SCOPE,
                Map.of(
                        "revisionId", cursor.revisionId().toString(),
                        "language", cursor.language(),
                        "category", encodeNullable(cursor.category()),
                        "regionCode", encodeNullable(cursor.regionCode()),
                        "limit", cursor.limit(),
                        "publishedAt", cursor.publishedAt().toString(),
                        "storyId", cursor.storyId()),
                clock.instant().plus(TTL)));
    }

    @Override
    public OdiiStoryCursor decode(String cursor) {
        try {
            var claims = cursorCodec.decode(cursor, SCOPE).claims();
            return new OdiiStoryCursor(
                    UUID.fromString(stringClaim(claims, "revisionId")),
                    stringClaim(claims, "language"),
                    decodeNullable(stringClaim(claims, "category")),
                    decodeNullable(stringClaim(claims, "regionCode")),
                    intClaim(claims, "limit"),
                    Instant.parse(stringClaim(claims, "publishedAt")),
                    stringClaim(claims, "storyId"));
        } catch (CursorExpiredException exception) {
            throw new OdiiCursorExpiredException();
        } catch (CursorInvalidException | IllegalArgumentException exception) {
            throw new OdiiCursorInvalidException(exception);
        }
    }

    private String stringClaim(Map<String, Object> claims, String name) {
        var value = claims.get(name);
        if (value instanceof String stringValue) {
            return stringValue;
        }
        throw new OdiiCursorInvalidException();
    }

    private int intClaim(Map<String, Object> claims, String name) {
        var value = claims.get(name);
        if (value instanceof Number number && number.doubleValue() == number.intValue()) {
            return number.intValue();
        }
        throw new OdiiCursorInvalidException();
    }

    private String encodeNullable(String value) {
        return value == null ? "" : value;
    }

    private String decodeNullable(String value) {
        return value.isEmpty() ? null : value;
    }
}
