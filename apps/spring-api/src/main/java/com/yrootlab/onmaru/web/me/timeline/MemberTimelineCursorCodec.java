package com.yrootlab.onmaru.web.me.timeline;

import com.yrootlab.onmaru.journey.timeline.TimelineCursor;
import com.yrootlab.onmaru.web.common.cursor.CursorCodec;
import com.yrootlab.onmaru.web.common.cursor.CursorExpiredException;
import com.yrootlab.onmaru.web.common.cursor.CursorInvalidException;
import com.yrootlab.onmaru.web.common.cursor.CursorPayload;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.YearMonth;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

final class MemberTimelineCursorCodec {

    private static final String SCOPE = "member-timeline:v1";
    private static final Duration TTL = Duration.ofMinutes(10);

    private final CursorCodec codec;
    private final Clock clock;

    MemberTimelineCursorCodec(CursorCodec codec, Clock clock) {
        this.codec = Objects.requireNonNull(codec, "codec");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    String encode(TimelineCursor cursor) {
        return codec.encode(new CursorPayload(SCOPE, Map.of(
                "memberId", cursor.memberId().toString(),
                "month", cursor.month().toString(),
                "limit", cursor.limit(),
                "asOf", cursor.asOf().toString(),
                "lastOccurredAt", cursor.lastOccurredAt().toString(),
                "lastId", cursor.lastId()), clock.instant().plus(TTL)));
    }

    TimelineCursor decode(String value) {
        if (value == null || value.isBlank() || value.length() > 512) {
            throw new CursorInvalidException();
        }
        var claims = codec.decode(value, SCOPE).claims();
        try {
            return new TimelineCursor(
                    UUID.fromString(stringClaim(claims, "memberId")),
                    YearMonth.parse(stringClaim(claims, "month")),
                    intClaim(claims, "limit"),
                    Instant.parse(stringClaim(claims, "asOf")),
                    Instant.parse(stringClaim(claims, "lastOccurredAt")),
                    stringClaim(claims, "lastId"));
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
}
