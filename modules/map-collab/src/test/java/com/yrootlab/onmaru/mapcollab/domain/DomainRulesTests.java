package com.yrootlab.onmaru.mapcollab.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DomainRulesTests {

    @Test
    void roomStateTransitionsAndExpiry() {
        UUID roomId = UUID.randomUUID();
        UUID creatorId = UUID.randomUUID();
        Instant now = Instant.parse("2026-11-10T00:00:00Z");
        Instant expiresAt = now.plus(72, ChronoUnit.HOURS);

        Room room = new Room(roomId, creatorId, RoomStatus.ACTIVE, now, expiresAt, 1L);
        assertThat(room.isActive()).isTrue();
        assertThat(room.isExpired(now.plus(71, ChronoUnit.HOURS))).isFalse();
        assertThat(room.isExpired(now.plus(72, ChronoUnit.HOURS))).isTrue();
        assertThat(room.isExpired(now.plus(73, ChronoUnit.HOURS))).isTrue();

        Room closedRoom = room.close(now.plus(1, ChronoUnit.HOURS), creatorId);
        assertThat(closedRoom.isActive()).isFalse();
        assertThat(closedRoom.status()).isEqualTo(RoomStatus.CLOSED);
        assertThat(closedRoom.closedAt()).isEqualTo(now.plus(1, ChronoUnit.HOURS));
        assertThat(closedRoom.closedBy()).isEqualTo(creatorId);
    }

    @Test
    void pinActiveStatusAndRemoval() {
        UUID pinId = UUID.randomUUID();
        UUID roomId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        Instant now = Instant.parse("2026-11-10T00:00:00Z");

        Pin pin = new Pin(pinId, roomId, "p-test", "테스트 장소", 37.5, 127.0, userId, now, 1L);
        assertThat(pin.isActive()).isTrue();
        assertThat(pin.removedAt()).isNull();

        UUID removerId = UUID.randomUUID();
        Instant removedAt = now.plus(1, ChronoUnit.HOURS);
        Pin removedPin = pin.remove(removerId, removedAt, 2L);

        assertThat(removedPin.isActive()).isFalse();
        assertThat(removedPin.removedBy()).isEqualTo(removerId);
        assertThat(removedPin.removedAt()).isEqualTo(removedAt);
        assertThat(removedPin.removedSeq()).isEqualTo(2L);

        assertThatThrownBy(() -> removedPin.remove(removerId, removedAt.plus(1, ChronoUnit.MINUTES), 3L))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void inviteValidityCheck() {
        UUID inviteId = UUID.randomUUID();
        UUID roomId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        Instant now = Instant.parse("2026-11-10T00:00:00Z");
        Instant expiresAt = now.plus(72, ChronoUnit.HOURS);

        Invite invite = new Invite(inviteId, roomId, "hash", userId, now, expiresAt);
        assertThat(invite.isValid(now)).isTrue();
        assertThat(invite.isRevoked()).isFalse();
        assertThat(invite.isExpired(now.plus(71, ChronoUnit.HOURS))).isFalse();
        assertThat(invite.isExpired(now.plus(72, ChronoUnit.HOURS))).isTrue();
        assertThat(invite.isValid(now.plus(72, ChronoUnit.HOURS))).isFalse();

        Invite revoked = invite.revoke(now.plus(1, ChronoUnit.HOURS));
        assertThat(revoked.isRevoked()).isTrue();
        assertThat(revoked.isValid(now.plus(2, ChronoUnit.HOURS))).isFalse();
    }
}
