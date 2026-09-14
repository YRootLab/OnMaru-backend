package com.yrootlab.onmaru.web.common.cursor;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CursorCodecTests {

    private static final Instant NOW = Instant.parse("2026-09-14T00:00:00Z");

    private final CursorCodec codec = new CursorCodec(
            new ObjectMapper(),
            CursorSigningKey.fromUtf8("0123456789abcdef0123456789abcdef"),
            Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void decodesCursorOnlyWhenScopeAndSignatureMatch() {
        var cursor = codec.encode(new CursorPayload(
                "visit-reviews:REGION:KR-11",
                Map.of("limit", 20, "lastId", "review-100"),
                NOW.plusSeconds(600)));

        var decoded = codec.decode(cursor, "visit-reviews:REGION:KR-11");

        assertThat(decoded.claims())
                .containsEntry("limit", 20)
                .containsEntry("lastId", "review-100");
    }

    @Test
    void rejectsTamperedCursorAsInvalid() {
        var cursor = codec.encode(new CursorPayload(
                "visit-reviews:ALL",
                Map.of("limit", 20, "lastId", "review-100"),
                NOW.plusSeconds(600)));
        var tampered = cursor.substring(0, cursor.length() - 2) + "xx";

        assertThatThrownBy(() -> codec.decode(tampered, "visit-reviews:ALL"))
                .isInstanceOf(CursorInvalidException.class);
    }

    @Test
    void rejectsExpiredCursorSeparatelyFromMalformedCursor() {
        var cursor = codec.encode(new CursorPayload(
                "saved-resources:member-1",
                Map.of("limit", 20, "lastId", "saved-100"),
                NOW.minusSeconds(1)));

        assertThatThrownBy(() -> codec.decode(cursor, "saved-resources:member-1"))
                .isInstanceOf(CursorExpiredException.class);
    }
}
