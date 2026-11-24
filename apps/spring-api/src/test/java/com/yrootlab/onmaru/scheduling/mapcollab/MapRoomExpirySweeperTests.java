package com.yrootlab.onmaru.scheduling.mapcollab;

import com.yrootlab.onmaru.mapcollab.domain.Room;
import com.yrootlab.onmaru.mapcollab.domain.RoomEvent;
import com.yrootlab.onmaru.mapcollab.domain.RoomEventType;
import com.yrootlab.onmaru.mapcollab.domain.RoomStatus;
import com.yrootlab.onmaru.mapcollab.port.RoomRepository;
import com.yrootlab.onmaru.mapcollab.runtime.RoomRegistry;
import com.yrootlab.onmaru.web.mapcollab.ws.MapCollabSessionRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MapRoomExpirySweeperTests {

    private RoomRepository roomRepository;
    private RoomRegistry roomRegistry;
    private MapCollabSessionRegistry sessionRegistry;
    private Clock clock;
    private MapRoomExpirySweeper sweeper;

    private final Instant now = Instant.parse("2026-11-10T12:00:00Z");

    @BeforeEach
    void setUp() {
        roomRepository = mock(RoomRepository.class);
        roomRegistry = mock(RoomRegistry.class);
        sessionRegistry = mock(MapCollabSessionRegistry.class);
        clock = Clock.fixed(now, ZoneOffset.UTC);
        sweeper = new MapRoomExpirySweeper(roomRepository, roomRegistry, sessionRegistry, clock);
    }

    @Test
    @DisplayName("T11: 만료 방이 없으면 0을 반환하고 아무 동작도 하지 않는다")
    void sweepNoExpiredRooms() {
        when(roomRepository.findExpiredActiveRooms(now, 50)).thenReturn(List.of());

        int count = sweeper.sweepOnce(now);

        assertThat(count).isEqualTo(0);
        verify(roomRepository, never()).closeRoom(any(), any(), any(), any());
        verify(sessionRegistry, never()).closeRoomSessions(any(), anyInt(), any());
    }

    @Test
    @DisplayName("T11: 만료 방을 CLOSED로 전환하고 ROOM_CLOSED 이벤트(actor NULL) 기록 및 4010 통지")
    void sweepExpiredRoomsClosesAndNotifies() {
        UUID roomId = UUID.randomUUID();
        UUID creatorId = UUID.randomUUID();
        Room expiredRoom = new Room(
                roomId,
                creatorId,
                RoomStatus.ACTIVE,
                now.minusSeconds(3600),
                now.minusSeconds(60),
                null,
                null,
                5L
        );

        when(roomRepository.findExpiredActiveRooms(now, 50)).thenReturn(List.of(expiredRoom));

        int count = sweeper.sweepOnce(now);

        assertThat(count).isEqualTo(1);

        ArgumentCaptor<RoomEvent> eventCaptor = ArgumentCaptor.forClass(RoomEvent.class);
        verify(roomRepository).closeRoom(eq(roomId), eq(now), eq(null), eventCaptor.capture());

        RoomEvent closeEvent = eventCaptor.getValue();
        assertThat(closeEvent.roomId()).isEqualTo(roomId);
        assertThat(closeEvent.seq()).isEqualTo(6L);
        assertThat(closeEvent.eventType()).isEqualTo(RoomEventType.ROOM_CLOSED);
        assertThat(closeEvent.actorMemberId()).isNull(); // actor NULL as per spec
        assertThat(closeEvent.createdAt()).isEqualTo(now);

        verify(roomRegistry).remove(roomId);
        verify(sessionRegistry).closeRoomSessions(roomId, 4010, "ROOM_CLOSED");
    }
}
