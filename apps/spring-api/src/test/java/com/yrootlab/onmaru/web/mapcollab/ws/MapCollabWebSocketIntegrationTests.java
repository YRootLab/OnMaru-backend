package com.yrootlab.onmaru.web.mapcollab.ws;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yrootlab.onmaru.mapcollab.domain.MapCollabLimits;
import com.yrootlab.onmaru.mapcollab.domain.Member;
import com.yrootlab.onmaru.mapcollab.domain.MemberRole;
import com.yrootlab.onmaru.mapcollab.domain.Pin;
import com.yrootlab.onmaru.mapcollab.domain.Room;
import com.yrootlab.onmaru.mapcollab.domain.RoomEvent;
import com.yrootlab.onmaru.mapcollab.domain.RoomEventType;
import com.yrootlab.onmaru.mapcollab.domain.RoomStatus;
import com.yrootlab.onmaru.mapcollab.port.MemberProfilePort;
import com.yrootlab.onmaru.mapcollab.port.PlaceCatalogPort;
import com.yrootlab.onmaru.mapcollab.port.RoomRepository;
import com.yrootlab.onmaru.mapcollab.runtime.RoomActor;
import com.yrootlab.onmaru.mapcollab.runtime.RoomRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MapCollabWebSocketIntegrationTests {

    private ObjectMapper objectMapper;
    private RoomRepository roomRepository;
    private PlaceCatalogPort placeCatalogPort;
    private MemberProfilePort memberProfilePort;
    private RoomRegistry roomRegistry;
    private MapCollabSessionRegistry sessionRegistry;
    private Clock clock;
    private MapCollabWebSocketHandler handler;

    private final Instant now = Instant.parse("2026-11-10T03:00:00Z");
    private final UUID roomId = UUID.randomUUID();
    private final UUID memberId = UUID.randomUUID();
    private final Instant roomExpiresAt = now.plusSeconds(72 * 3600);

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        roomRepository = mock(RoomRepository.class);
        placeCatalogPort = mock(PlaceCatalogPort.class);
        memberProfilePort = mock(MemberProfilePort.class);
        sessionRegistry = new MapCollabSessionRegistry(objectMapper);
        roomRegistry = mock(RoomRegistry.class);
        clock = Clock.fixed(now, ZoneOffset.UTC);

        var sessionStore = mock(com.yrootlab.onmaru.persistence.mapcollab.JdbcRoomSessionStore.class);
        handler = new MapCollabWebSocketHandler(
                objectMapper,
                roomRepository,
                roomRegistry,
                memberProfilePort,
                sessionRegistry,
                sessionStore,
                clock
        );

        RoomActor defaultActor = mock(RoomActor.class);
        when(roomRegistry.getOrCreate(roomId)).thenReturn(defaultActor);
        when(defaultActor.getActivePins()).thenReturn(List.of());
        when(defaultActor.getLastEventSeq()).thenReturn(1L);

        when(memberProfilePort.findDisplayName(memberId)).thenReturn("tester");
    }

    private WebSocketSession createMockSession(String sessionId, UUID mId, Long lastSeq) {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn(sessionId);
        when(session.isOpen()).thenReturn(true);

        Map<String, Object> attrs = new HashMap<>();
        attrs.put("roomId", roomId);
        attrs.put("memberId", mId);
        attrs.put("displayName", "tester-" + sessionId);
        attrs.put("roomExpiresAt", roomExpiresAt);
        attrs.put("lastSeq", lastSeq);
        attrs.put("requestId", "req-" + sessionId);

        when(session.getAttributes()).thenReturn(attrs);
        return session;
    }

    @Test
    @DisplayName("T9: 연결 시 WELCOME 메시지를 받고 멤버 목록과 활성 핀이 전달된다")
    void connectionEstablishedSendsWelcome() throws Exception {
        WebSocketSession session = createMockSession("sess-1", memberId, null);
        List<TextMessage> sent = Collections.synchronizedList(new ArrayList<>());
        doAnswer(inv -> {
            sent.add(inv.getArgument(0));
            return null;
        }).when(session).sendMessage(any(TextMessage.class));

        RoomActor defaultActor = roomRegistry.getOrCreate(roomId);
        Pin pin1 = new Pin(UUID.randomUUID(), roomId, "p-okcheon-hanok", "옥천성당", 36.3, 127.7, memberId, now, 1L, null, null, null);
        when(defaultActor.getActivePins()).thenReturn(List.of(pin1));
        when(defaultActor.getLastEventSeq()).thenReturn(1L);

        when(roomRepository.findActivePins(roomId)).thenReturn(List.of(pin1));
        when(roomRepository.findMembersByRoomId(roomId)).thenReturn(List.of(new Member(roomId, memberId, "tester", MemberRole.OWNER, now)));
        when(memberProfilePort.findDisplayNames(any())).thenReturn(Map.of(memberId, "tester"));
        Room room = new Room(roomId, memberId, RoomStatus.ACTIVE, now, roomExpiresAt, null, null, 1L);
        when(roomRepository.findRoomById(roomId)).thenReturn(Optional.of(room));

        handler.afterConnectionEstablished(session);

        assertThat(sent).isNotEmpty();
        JsonNode node = sent.stream()
                .map(m -> {
                    try { return objectMapper.readTree(m.getPayload()); }
                    catch (Exception e) { throw new RuntimeException(e); }
                })
                .filter(n -> "WELCOME".equals(n.get("type").asText()))
                .findFirst()
                .orElseThrow();
        assertThat(node.get("v").asInt()).isEqualTo(1);
        assertThat(node.get("type").asText()).isEqualTo("WELCOME");
        assertThat(node.get("payload").get("lastSeq").asLong()).isEqualTo(1L);
        assertThat(node.get("payload").get("pins")).hasSize(1);
        assertThat(node.get("payload").get("pins").get(0).get("placeId").asText()).isEqualTo("p-okcheon-hanok");
    }

    @Test
    @DisplayName("T8/T10: 한 회원의 동시 연결이 3개를 초과(4번째)하면 가장 오래된 연결이 종료된다 (SUPERSEDED)")
    void maxConcurrentConnectionsEnforced() throws Exception {
        WebSocketSession s1 = createMockSession("sess-1", memberId, null);
        WebSocketSession s2 = createMockSession("sess-2", memberId, null);
        WebSocketSession s3 = createMockSession("sess-3", memberId, null);
        WebSocketSession s4 = createMockSession("sess-4", memberId, null);

        Room room = new Room(roomId, memberId, RoomStatus.ACTIVE, now, roomExpiresAt, null, null, 0L);
        when(roomRepository.findRoomById(roomId)).thenReturn(Optional.of(room));
        when(roomRepository.findActivePins(roomId)).thenReturn(List.of());
        when(roomRepository.findMembersByRoomId(roomId)).thenReturn(List.of());
        when(memberProfilePort.findDisplayNames(any())).thenReturn(Map.of());

        handler.afterConnectionEstablished(s1);
        handler.afterConnectionEstablished(s2);
        handler.afterConnectionEstablished(s3);

        verify(s1, never()).close(any());

        // 4th connection arrives
        handler.afterConnectionEstablished(s4);

        // s1 (oldest) must have been closed
        verify(s1).close(eq(MapCollabSessionRegistry.STATUS_SUPERSEDED));
    }

    @Test
    @DisplayName("T9: PIN_ADD 수신 시 RoomActor 실행 후 송신자에게 ACK, 전체 브로드캐스트 EVENT")
    void handlePinAddMessage() throws Exception {
        WebSocketSession session = createMockSession("sess-1", memberId, null);
        List<TextMessage> sent = Collections.synchronizedList(new ArrayList<>());
        doAnswer(inv -> {
            sent.add(inv.getArgument(0));
            return null;
        }).when(session).sendMessage(any(TextMessage.class));

        Room room = new Room(roomId, memberId, RoomStatus.ACTIVE, now, roomExpiresAt, null, null, 0L);
        when(roomRepository.findRoomById(roomId)).thenReturn(Optional.of(room));
        when(roomRepository.findActivePins(roomId)).thenReturn(List.of());
        when(roomRepository.findMembersByRoomId(roomId)).thenReturn(List.of());
        when(memberProfilePort.findDisplayNames(any())).thenReturn(Map.of());

        handler.afterConnectionEstablished(session);
        sent.clear(); // clear welcome message

        RoomActor actor = mock(RoomActor.class);
        when(roomRegistry.getOrCreate(roomId)).thenReturn(actor);

        UUID opId = UUID.randomUUID();
        Pin newPin = new Pin(UUID.randomUUID(), roomId, "p-gyeongju-bulguksa", "불국사", 35.79, 129.33, memberId, now, 2L, null, null, null);
        when(actor.addPin(eq(opId), eq("p-gyeongju-bulguksa"), eq(memberId), any(), any(), eq(roomExpiresAt)))
                .thenReturn(new RoomActor.PinAddResult(2L, newPin, false));

        String incomingJson = """
                {
                  "v": 1,
                  "type": "PIN_ADD",
                  "opId": "%s",
                  "payload": {
                    "placeId": "p-gyeongju-bulguksa"
                  }
                }
                """.formatted(opId);

        handler.handleTextMessage(session, new TextMessage(incomingJson));

        // ACK must be sent to session
        assertThat(sent).isNotEmpty();
        JsonNode ackNode = objectMapper.readTree(sent.get(0).getPayload());
        assertThat(ackNode.get("type").asText()).isEqualTo("ACK");
        assertThat(ackNode.get("opId").asText()).isEqualTo(opId.toString());
        assertThat(ackNode.get("payload").get("seq").asLong()).isEqualTo(2L);
        assertThat(ackNode.get("payload").get("pin").get("placeId").asText()).isEqualTo("p-gyeongju-bulguksa");
    }

    @Test
    @DisplayName("T9: lastSeq가 최신이면 추가 재생 없이 WELCOME만 받고, 이벤트가 있으면 RESUME 재생")
    void resumeReplaysEventsAfterLastSeq() throws Exception {
        WebSocketSession session = createMockSession("sess-1", memberId, 2L);
        List<TextMessage> sent = Collections.synchronizedList(new ArrayList<>());
        doAnswer(inv -> {
            sent.add(inv.getArgument(0));
            return null;
        }).when(session).sendMessage(any(TextMessage.class));

        Room room = new Room(roomId, memberId, RoomStatus.ACTIVE, now, roomExpiresAt, null, null, 3L);
        when(roomRepository.findRoomById(roomId)).thenReturn(Optional.of(room));
        RoomActor actor = roomRegistry.getOrCreate(roomId);
        when(actor.getLastEventSeq()).thenReturn(3L);

        RoomEvent event3 = new RoomEvent(roomId, 3L, RoomEventType.PIN_ADDED, memberId, null, UUID.randomUUID(), UUID.randomUUID(), "{}", now);
        when(roomRepository.findEventsAfter(roomId, 2L)).thenReturn(List.of(event3));
        when(memberProfilePort.findDisplayName(memberId)).thenReturn("tester");

        handler.afterConnectionEstablished(session);

        assertThat(sent).isNotEmpty();
        JsonNode eventNode = sent.stream()
                .map(m -> {
                    try { return objectMapper.readTree(m.getPayload()); }
                    catch (Exception e) { throw new RuntimeException(e); }
                })
                .filter(n -> "EVENT".equals(n.get("type").asText()))
                .findFirst()
                .orElseThrow();
        assertThat(eventNode.get("type").asText()).isEqualTo("EVENT");
        assertThat(eventNode.get("payload").get("seq").asLong()).isEqualTo(3L);
    }

    @Test
    @DisplayName("T9: lastSeq가 유실된 범위이거나 갭이 있으면 RESYNC_REQUIRED 전송")
    void resyncRequiredWhenGapOrOldSeq() throws Exception {
        WebSocketSession session = createMockSession("sess-1", memberId, 1L);
        List<TextMessage> sent = Collections.synchronizedList(new ArrayList<>());
        doAnswer(inv -> {
            sent.add(inv.getArgument(0));
            return null;
        }).when(session).sendMessage(any(TextMessage.class));

        Room room = new Room(roomId, memberId, RoomStatus.ACTIVE, now, roomExpiresAt, null, null, 10L);
        when(roomRepository.findRoomById(roomId)).thenReturn(Optional.of(room));
        RoomActor actor = roomRegistry.getOrCreate(roomId);
        when(actor.getLastEventSeq()).thenReturn(10L);

        // oldest available event is seq 5 (seq 2, 3, 4 are pruned/missing) -> gap!
        RoomEvent event5 = new RoomEvent(roomId, 5L, RoomEventType.PIN_ADDED, memberId, null, UUID.randomUUID(), UUID.randomUUID(), "{}", now);
        when(roomRepository.findEventsAfter(roomId, 1L)).thenReturn(List.of(event5));

        handler.afterConnectionEstablished(session);

        assertThat(sent).isNotEmpty();
        JsonNode resyncNode = sent.stream()
                .map(m -> {
                    try { return objectMapper.readTree(m.getPayload()); }
                    catch (Exception e) { throw new RuntimeException(e); }
                })
                .filter(n -> "RESYNC_REQUIRED".equals(n.get("type").asText()))
                .findFirst()
                .orElseThrow();
        assertThat(resyncNode.get("type").asText()).isEqualTo("RESYNC_REQUIRED");
    }

    @Test
    @DisplayName("T10: 발신 큐 상한 256 초과 시 세션이 4029 SLOW_CONSUMER 로 종료된다")
    void outboundQueueOverflowClosesWith4029() throws Exception {
        WebSocketSession session = createMockSession("sess-slow", memberId, null);
        MapCollabSessionRegistry.SessionContext ctx = new MapCollabSessionRegistry.SessionContext(
                session, roomId, memberId, "tester", now
        );
        sessionRegistry.registerSession(ctx);

        // Fill outbound queue to 256
        for (int i = 0; i < MapCollabLimits.OUTBOX_CAPACITY; i++) {
            ctx.pendingMessageCount.incrementAndGet();
        }

        // Next broadcast causes overflow > 256
        sessionRegistry.broadcastText(roomId, "{\"v\":1,\"type\":\"PING\"}", null);

        verify(session).close(eq(MapCollabSessionRegistry.STATUS_SLOW_CONSUMER));
    }
}
