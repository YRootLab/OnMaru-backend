package com.yrootlab.onmaru.mapcollab.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TokenUtilsTests {

    @Test
    void generatesValidBase64UrlTokens() {
        for (int i = 0; i < 50; i++) {
            String token = TokenUtils.generateRawToken();
            assertThat(token).hasSize(43);
            assertThat(TokenUtils.isValidTokenFormat(token)).isTrue();
        }
    }

    @Test
    void rejectsInvalidTokens() {
        assertThat(TokenUtils.isValidTokenFormat(null)).isFalse();
        assertThat(TokenUtils.isValidTokenFormat("")).isFalse();
        assertThat(TokenUtils.isValidTokenFormat("too-short")).isFalse();
        assertThat(TokenUtils.isValidTokenFormat("invalid+character/with=padding==")).isFalse();
    }

    @Test
    void hashesToSha256LowercaseHex() {
        String token = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk";
        String hash = TokenUtils.sha256Hex(token);
        assertThat(hash).hasSize(64);
        assertThat(TokenUtils.isValidHashFormat(hash)).isTrue();
        assertThat(hash).isEqualTo(hash.toLowerCase());
        assertThat(TokenUtils.sha256Hex(token)).isEqualTo(hash);
    }

    @Test
    void calculatesInviteExpiresAtCorrectly() {
        Instant now = Instant.parse("2026-11-10T00:00:00Z");

        // When room expires in 72 hours, invite expires in 72 hours
        Instant roomExpiresAt72 = now.plus(72, ChronoUnit.HOURS);
        Instant inviteExpiresAt1 = TokenUtils.calculateInviteExpiresAt(now, roomExpiresAt72);
        assertThat(inviteExpiresAt1).isEqualTo(roomExpiresAt72);

        // When room expires in 24 hours, invite cannot exceed room expiry
        Instant roomExpiresAt24 = now.plus(24, ChronoUnit.HOURS);
        Instant inviteExpiresAt2 = TokenUtils.calculateInviteExpiresAt(now, roomExpiresAt24);
        assertThat(inviteExpiresAt2).isEqualTo(roomExpiresAt24);

        // When room expires in 100 hours, invite expires in 72 hours
        Instant roomExpiresAt100 = now.plus(100, ChronoUnit.HOURS);
        Instant inviteExpiresAt3 = TokenUtils.calculateInviteExpiresAt(now, roomExpiresAt100);
        assertThat(inviteExpiresAt3).isEqualTo(now.plus(72, ChronoUnit.HOURS));
    }
}
