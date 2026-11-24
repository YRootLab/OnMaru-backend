package com.yrootlab.onmaru.web.mapcollab.ws;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yrootlab.onmaru.mapcollab.domain.Invite;
import com.yrootlab.onmaru.mapcollab.domain.Member;
import com.yrootlab.onmaru.mapcollab.domain.MemberRole;
import com.yrootlab.onmaru.mapcollab.domain.Pin;
import com.yrootlab.onmaru.mapcollab.domain.Room;
import com.yrootlab.onmaru.mapcollab.domain.RoomEvent;
import com.yrootlab.onmaru.mapcollab.domain.RoomStatus;
import com.yrootlab.onmaru.mapcollab.domain.UserRoomMembership;
import com.yrootlab.onmaru.mapcollab.port.CatalogPlaceInfo;
import com.yrootlab.onmaru.mapcollab.port.MemberProfilePort;
import com.yrootlab.onmaru.mapcollab.port.PlaceCatalogPort;
import com.yrootlab.onmaru.mapcollab.port.RoomRepository;
import com.yrootlab.onmaru.mapcollab.runtime.RoomActor;
import com.yrootlab.onmaru.mapcollab.runtime.RoomRegistry;
import com.yrootlab.onmaru.persistence.mapcollab.RoomSessionStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MapCollabThreeClientsSyncIntegrationTests {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Instant now = Instant.parse("2026-11-10T03:00:00Z");
    private final Clock clock = Clock.fixed(now, ZoneOffset.UTC);
    private final Instant roomExpiresAt = now.plusSeconds(72 * 3600);

    private final UUID roomId = UUID.randomUUID();
    private final UUID hostMemberId = UUID.randomUUID();
    private final UUID editor1MemberId = UUID.randomUUID();
    private final UUID editor2MemberId = UUID.randomUUID();

    private MapCollabSessionRegistry sessionRegistry;
    private RoomRegistry roomRegistry;
    private MapCollabWebSocketHandler handler;
    private InMemoryTestRoomRepository roomRepository;
    private PlaceCatalogPort placeCatalogPort;
    private MemberProfilePort memberProfilePort;

    static class InMemoryTestRoomRepository implements RoomRepository {
        private Room room;
        private final Map<UUID, Pin> pins = new LinkedHashMap<>();
        private final List<RoomEvent> events = new ArrayList<>();
        private final List<Member> members = new ArrayList<>();

        public InMemoryTestRoomRepository(Room room, List<Member> members) {
            this.room = room;
            this.members.addAll(members);
        }

        @Override
        public Optional<Room> findRoomById(UUID rId) {
            return rId.equals(room.id()) ? Optional.of(room) : Optional.empty();
        }

        @Override
        public boolean isMember(UUID rId, UUID memberId) {
            return members.stream().anyMatch(m -> m.memberId().equals(memberId));
        }

        @Override
        public Optional<MemberRole> findMemberRole(UUID rId, UUID memberId) {
            return members.stream().filter(m -> m.memberId().equals(memberId)).map(Member::role).findFirst();
        }

        @Override
        public List<Member> findMembersByRoomId(UUID rId) {
            return new ArrayList<>(members);
        }

        @Override
        public List<Pin> findActivePins(UUID rId) {
            return pins.values().stream().filter(Pin::isActive).toList();
        }

        @Override
        public Optional<Pin> findActivePinByPlaceId(UUID rId, String placeId) {
            return pins.values().stream().filter(p -> p.isActive() && p.placeId().equals(placeId)).findFirst();
        }

        @Override
        public Optional<Pin> findActivePinById(UUID rId, UUID pinId) {
            Pin p = pins.get(pinId);
            return (p != null && p.isActive()) ? Optional.of(p) : Optional.empty();
        }

        @Override
        public synchronized void addPin(Pin pin, RoomEvent event, long newLastEventSeq) {
            pins.put(pin.pinId(), pin);
            events.add(event);
            this.room = new Room(room.id(), room.createdBy(), room.status(), room.createdAt(), room.expiresAt(), room.closedAt(), room.closedBy(), newLastEventSeq);
        }

        @Override
        public synchronized void removePin(UUID rId, UUID pinId, UUID removedBy, Instant removedAt, long removedSeq, RoomEvent event, long newLastEventSeq) {
            Pin existing = pins.get(pinId);
            if (existing != null) {
                Pin removed = new Pin(existing.pinId(), existing.roomId(), existing.placeId(), existing.placeName(), existing.lat(), existing.lng(),
                        existing.addedBy(), existing.addedAt(), existing.order(), removedBy, removedAt, removedSeq);
                pins.put(pinId, removed);
            }
            events.add(event);
            this.room = new Room(room.id(), room.createdBy(), room.status(), room.createdAt(), room.expiresAt(), room.closedAt(), room.closedBy(), newLastEventSeq);
        }

        @Override
        public Optional<RoomEvent> findEventByOpId(UUID rId, UUID clientOpId) {
            return events.stream().filter(e -> clientOpId.equals(e.clientOpId())).findFirst();
        }

        @Override
        public List<RoomEvent> findEventsAfter(UUID rId, long lastSeq) {
            return events.stream().filter(e -> e.seq() > lastSeq).toList();
        }

        // Stubs
        @Override public void saveRoomWithInitialMemberAndEvent(Room r, Member m, RoomEvent e) { this.room = r; }
        @Override public void saveInvite(Invite invite, RoomEvent inviteEvent) {}
        @Override public Optional<Invite> findInviteByTokenHash(String tokenHash) { return Optional.empty(); }
        @Override public Optional<Invite> findInviteById(UUID rId, UUID inviteId) { return Optional.empty(); }
        @Override public void revokeInvite(UUID rId, UUID inviteId, Instant revokedAt, RoomEvent revokeEvent) {}
        @Override public long countActiveInvites(UUID rId, Instant now) { return 0; }
        @Override public void addMember(Member member, RoomEvent joinEvent) { members.add(member); }
        @Override public long countMembers(UUID rId) { return members.size(); }
        @Override public List<UserRoomMembership> findActiveRoomsByMemberId(UUID memberId, Instant now) { return List.of(); }
        @Override public List<Room> findExpiredActiveRooms(Instant now, int limit) { return List.of(); }
        @Override public void closeRoom(UUID rId, Instant closedAt, UUID closedBy, RoomEvent closeEvent) {}
    }

    @BeforeEach
    void setUp() {
        Room room = new Room(roomId, hostMemberId, RoomStatus.ACTIVE, now, roomExpiresAt, 0L);
        List<Member> members = List.of(
                new Member(roomId, hostMemberId, "호스트", MemberRole.OWNER, now),
                new Member(roomId, editor1MemberId, "에디터1", MemberRole.MEMBER, now),
                new Member(roomId, editor2MemberId, "에디터2", MemberRole.MEMBER, now)
        );
        roomRepository = new InMemoryTestRoomRepository(room, members);

        placeCatalogPort = mock(PlaceCatalogPort.class);
        when(placeCatalogPort.findPlaceByPublicId("p-gyeongju-bulguksa"))
                .thenReturn(Optional.of(new CatalogPlaceInfo("p-gyeongju-bulguksa", "불국사", 35.7900, 129.3300)));
        when(placeCatalogPort.findPlaceByPublicId("p-jeonju-hanok"))
                .thenReturn(Optional.of(new CatalogPlaceInfo("p-jeonju-hanok", "전주 한옥마을", 35.8150, 127.1530)));

        memberProfilePort = mock(MemberProfilePort.class);
        when(memberProfilePort.findDisplayName(hostMemberId)).thenReturn("호스트");
        when(memberProfilePort.findDisplayName(editor1MemberId)).thenReturn("에디터1");
        when(memberProfilePort.findDisplayName(editor2MemberId)).thenReturn("에디터2");
        when(memberProfilePort.findDisplayNames(any())).thenReturn(Map.of(
                hostMemberId, "호스트",
                editor1MemberId, "에디터1",
                editor2MemberId, "에디터2"
        ));

        sessionRegistry = new MapCollabSessionRegistry(objectMapper);
        roomRegistry = new RoomRegistry(roomRepository, placeCatalogPort, memberProfilePort, sessionRegistry);

        handler = new MapCollabWebSocketHandler(
                objectMapper,
                roomRepository,
                roomRegistry,
                memberProfilePort,
                sessionRegistry,
                RoomSessionStore.noOp(),
                clock
        );
    }

    private static class TestClient {
        final String name;
        final UUID memberId;
        final WebSocketSession session;
        final BlockingQueue<JsonNode> receivedMessages = new LinkedBlockingQueue<>();

        TestClient(String name, UUID memberId, WebSocketSession session) {
            this.name = name;
            this.memberId = memberId;
            this.session = session;
        }

        JsonNode awaitMessage(long timeoutMs) throws InterruptedException {
            return receivedMessages.poll(timeoutMs, TimeUnit.MILLISECONDS);
        }
    }

    private TestClient createClient(String sessionId, UUID memberId, String displayName) throws IOException {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn(sessionId);
        when(session.isOpen()).thenReturn(true);

        Map<String, Object> attrs = new HashMap<>();
        attrs.put("roomId", roomId);
        attrs.put("memberId", memberId);
        attrs.put("displayName", displayName);
        attrs.put("roomExpiresAt", roomExpiresAt);
        attrs.put("lastSeq", null);
        attrs.put("requestId", "req-" + sessionId);
        when(session.getAttributes()).thenReturn(attrs);

        TestClient client = new TestClient(displayName, memberId, session);

        doAnswer(inv -> {
            TextMessage textMessage = inv.getArgument(0);
            JsonNode tree = objectMapper.readTree(textMessage.getPayload());
            client.receivedMessages.offer(tree);
            return null;
        }).when(session).sendMessage(any(TextMessage.class));

        return client;
    }

    @Test
    @DisplayName("3명의 클라이언트가 동시 접속하여 핀 추가, 삭제를 수행할 때 실시간 브로드캐스트와 상태 동기화가 완벽하게 일치한다")
    void threeClientsPinAddAndRemoveSyncIntegration() throws Exception {
        // Given: 3명의 클라이언트 생성
        TestClient clientHost = createClient("sess-host", hostMemberId, "호스트");
        TestClient clientEditor1 = createClient("sess-ed1", editor1MemberId, "에디터1");
        TestClient clientEditor2 = createClient("sess-ed2", editor2MemberId, "에디터2");

        // 1. Host 연결 -> 본인 PRESENCE(ONLINE) 후 WELCOME
        handler.afterConnectionEstablished(clientHost.session);
        JsonNode hostSelfPresence = clientHost.awaitMessage(1000);
        assertThat(hostSelfPresence.get("type").asText()).isEqualTo("PRESENCE");
        JsonNode hostWelcome = clientHost.awaitMessage(1000);
        assertThat(hostWelcome.get("type").asText()).isEqualTo("WELCOME");
        assertThat(hostWelcome.get("payload").get("lastSeq").asLong()).isEqualTo(0L);
        assertThat(hostWelcome.get("payload").get("pins")).isEmpty();

        // 2. Editor 1 연결 -> Host에게 PRESENCE, Editor 1 본인에게 PRESENCE + WELCOME
        handler.afterConnectionEstablished(clientEditor1.session);
        JsonNode hostPresence1 = clientHost.awaitMessage(1000);
        assertThat(hostPresence1.get("type").asText()).isEqualTo("PRESENCE");
        assertThat(hostPresence1.get("payload").get("memberId").asText()).isEqualTo(editor1MemberId.toString());

        JsonNode ed1SelfPresence = clientEditor1.awaitMessage(1000);
        assertThat(ed1SelfPresence.get("type").asText()).isEqualTo("PRESENCE");
        JsonNode editor1Welcome = clientEditor1.awaitMessage(1000);
        assertThat(editor1Welcome.get("type").asText()).isEqualTo("WELCOME");

        // 3. Editor 2 연결 -> Host와 Editor 1에게 PRESENCE, Editor 2 본인에게 PRESENCE + WELCOME
        handler.afterConnectionEstablished(clientEditor2.session);
        JsonNode hostPresence2 = clientHost.awaitMessage(1000);
        assertThat(hostPresence2.get("type").asText()).isEqualTo("PRESENCE");
        JsonNode ed1Presence2 = clientEditor1.awaitMessage(1000);
        assertThat(ed1Presence2.get("type").asText()).isEqualTo("PRESENCE");

        JsonNode ed2SelfPresence = clientEditor2.awaitMessage(1000);
        assertThat(ed2SelfPresence.get("type").asText()).isEqualTo("PRESENCE");
        JsonNode editor2Welcome = clientEditor2.awaitMessage(1000);
        assertThat(editor2Welcome.get("type").asText()).isEqualTo("WELCOME");

        // --------------------------------------------------------------------------
        // Action 1: Client 1 (Host)이 '불국사' 핀 추가
        // --------------------------------------------------------------------------
        UUID opId1 = UUID.randomUUID();
        String pinAddMsg1 = """
                {
                  "v": 1,
                  "type": "PIN_ADD",
                  "opId": "%s",
                  "payload": {
                    "placeId": "p-gyeongju-bulguksa"
                  }
                }
                """.formatted(opId1);

        handler.handleTextMessage(clientHost.session, new TextMessage(pinAddMsg1));

        // 검증 1: Host 본인은 ACK와 EVENT를 모두 수신
        JsonNode hostMsgA = clientHost.awaitMessage(1000);
        JsonNode hostMsgB = clientHost.awaitMessage(1000);
        JsonNode hostAck1 = "ACK".equals(hostMsgA.get("type").asText()) ? hostMsgA : hostMsgB;
        JsonNode hostEvt1 = "EVENT".equals(hostMsgA.get("type").asText()) ? hostMsgA : hostMsgB;

        assertThat(hostAck1.get("type").asText()).isEqualTo("ACK");
        assertThat(hostAck1.get("opId").asText()).isEqualTo(opId1.toString());
        assertThat(hostAck1.get("payload").get("seq").asLong()).isEqualTo(1L);
        UUID pin1Id = UUID.fromString(hostAck1.get("payload").get("pin").get("pinId").asText());

        // 검증 1: Editor 1, Editor 2는 실시간 EVENT(PIN_ADDED, seq=1) 브로드캐스트 도착
        JsonNode ed1Evt1 = clientEditor1.awaitMessage(1000);
        JsonNode ed2Evt1 = clientEditor2.awaitMessage(1000);

        for (JsonNode evt : List.of(hostEvt1, ed1Evt1, ed2Evt1)) {
            assertThat(evt.get("type").asText()).isEqualTo("EVENT");
            assertThat(evt.get("payload").get("seq").asLong()).isEqualTo(1L);
            assertThat(evt.get("payload").get("eventType").asText()).isEqualTo("PIN_ADDED");
            assertThat(evt.get("payload").get("pin").get("placeId").asText()).isEqualTo("p-gyeongju-bulguksa");
            assertThat(evt.get("payload").get("pin").get("placeName").asText()).isEqualTo("불국사");
            assertThat(evt.get("payload").get("actor").get("displayName").asText()).isEqualTo("호스트");
        }

        // --------------------------------------------------------------------------
        // Action 2: Client 2 (Editor 1)가 '전주 한옥마을' 핀 추가
        // --------------------------------------------------------------------------
        UUID opId2 = UUID.randomUUID();
        String pinAddMsg2 = """
                {
                  "v": 1,
                  "type": "PIN_ADD",
                  "opId": "%s",
                  "payload": {
                    "placeId": "p-jeonju-hanok"
                  }
                }
                """.formatted(opId2);

        handler.handleTextMessage(clientEditor1.session, new TextMessage(pinAddMsg2));

        // 검증 2: Editor 1 본인은 ACK와 EVENT 모두 수신
        JsonNode ed1MsgA = clientEditor1.awaitMessage(1000);
        JsonNode ed1MsgB = clientEditor1.awaitMessage(1000);
        JsonNode ed1Ack2 = "ACK".equals(ed1MsgA.get("type").asText()) ? ed1MsgA : ed1MsgB;
        JsonNode ed1Evt2 = "EVENT".equals(ed1MsgA.get("type").asText()) ? ed1MsgA : ed1MsgB;

        assertThat(ed1Ack2.get("type").asText()).isEqualTo("ACK");
        assertThat(ed1Ack2.get("opId").asText()).isEqualTo(opId2.toString());
        assertThat(ed1Ack2.get("payload").get("seq").asLong()).isEqualTo(2L);
        UUID pin2Id = UUID.fromString(ed1Ack2.get("payload").get("pin").get("pinId").asText());

        // 검증 2: Host와 Editor 2도 EVENT(PIN_ADDED, seq=2) 브로드캐스트 도착
        JsonNode hostEvt2 = clientHost.awaitMessage(1000);
        JsonNode ed2Evt2 = clientEditor2.awaitMessage(1000);

        for (JsonNode evt : List.of(hostEvt2, ed1Evt2, ed2Evt2)) {
            assertThat(evt.get("type").asText()).isEqualTo("EVENT");
            assertThat(evt.get("payload").get("seq").asLong()).isEqualTo(2L);
            assertThat(evt.get("payload").get("eventType").asText()).isEqualTo("PIN_ADDED");
            assertThat(evt.get("payload").get("pin").get("placeId").asText()).isEqualTo("p-jeonju-hanok");
            assertThat(evt.get("payload").get("actor").get("displayName").asText()).isEqualTo("에디터1");
        }

        // --------------------------------------------------------------------------
        // Action 3: Client 3 (Editor 2)가 첫 번째 핀(불국사) 제거
        // --------------------------------------------------------------------------
        UUID opId3 = UUID.randomUUID();
        String pinRemoveMsg = """
                {
                  "v": 1,
                  "type": "PIN_REMOVE",
                  "opId": "%s",
                  "payload": {
                    "pinId": "%s"
                  }
                }
                """.formatted(opId3, pin1Id);

        handler.handleTextMessage(clientEditor2.session, new TextMessage(pinRemoveMsg));

        // 검증 3: Editor 2 본인은 ACK와 EVENT 모두 수신
        JsonNode ed2MsgA = clientEditor2.awaitMessage(1000);
        JsonNode ed2MsgB = clientEditor2.awaitMessage(1000);
        JsonNode ed2Ack3 = "ACK".equals(ed2MsgA.get("type").asText()) ? ed2MsgA : ed2MsgB;
        JsonNode ed2Evt3 = "EVENT".equals(ed2MsgA.get("type").asText()) ? ed2MsgA : ed2MsgB;

        assertThat(ed2Ack3.get("type").asText()).isEqualTo("ACK");
        assertThat(ed2Ack3.get("opId").asText()).isEqualTo(opId3.toString());
        assertThat(ed2Ack3.get("payload").get("seq").asLong()).isEqualTo(3L);
        assertThat(ed2Ack3.get("payload").get("pinId").asText()).isEqualTo(pin1Id.toString());

        // 검증 3: Host와 Editor 1도 EVENT(PIN_REMOVED, seq=3) 브로드캐스트 도착
        JsonNode hostEvt3 = clientHost.awaitMessage(1000);
        JsonNode ed1Evt3 = clientEditor1.awaitMessage(1000);

        for (JsonNode evt : List.of(hostEvt3, ed1Evt3, ed2Evt3)) {
            assertThat(evt.get("type").asText()).isEqualTo("EVENT");
            assertThat(evt.get("payload").get("seq").asLong()).isEqualTo(3L);
            assertThat(evt.get("payload").get("eventType").asText()).isEqualTo("PIN_REMOVED");
            assertThat(evt.get("payload").get("pinId").asText()).isEqualTo(pin1Id.toString());
            assertThat(evt.get("payload").get("actor").get("displayName").asText()).isEqualTo("에디터2");
        }

        // --------------------------------------------------------------------------
        // 최종 정합성 검증:
        // 서버 RoomActor 및 RoomRepository의 최종 상태와 3명의 이벤트 순서가 완벽히 일치해야 함
        // --------------------------------------------------------------------------
        RoomActor actor = roomRegistry.getOrCreate(roomId);
        assertThat(actor.getLastEventSeq()).isEqualTo(3L);

        // 남은 활성 핀은 오직 '전주 한옥마을' 1개뿐이어야 함
        List<Pin> activePins = actor.getActivePins();
        assertThat(activePins).hasSize(1);
        assertThat(activePins.get(0).pinId()).isEqualTo(pin2Id);
        assertThat(activePins.get(0).placeId()).isEqualTo("p-jeonju-hanok");

        // DB에 저장된 상태도 seq=3, 활성 핀 1개로 일치
        assertThat(roomRepository.findActivePins(roomId)).hasSize(1);
        assertThat(roomRepository.findRoomById(roomId).orElseThrow().lastEventSeq()).isEqualTo(3L);

        // --------------------------------------------------------------------------
        // 커서 동기화 검증: Editor 1이 마우스 좌표 전송 시 다른 클라이언트에게 CURSORS 브로드캐스트
        // --------------------------------------------------------------------------
        String cursorMsg = """
                {
                  "v": 1,
                  "type": "CURSOR",
                  "payload": {
                    "lat": 35.8155,
                    "lng": 127.1535
                  }
                }
                """;
        handler.handleTextMessage(clientEditor1.session, new TextMessage(cursorMsg));

        // 80ms 주기 버퍼링 플러시 대기
        Thread.sleep(150);

        JsonNode hostCursor = clientHost.awaitMessage(1000);
        JsonNode ed2Cursor = clientEditor2.awaitMessage(1000);

        assertThat(hostCursor).isNotNull();
        assertThat(hostCursor.get("type").asText()).isEqualTo("CURSORS");
        assertThat(hostCursor.get("payload").get("cursors")).isNotEmpty();
        assertThat(hostCursor.get("payload").get("cursors").get(0).get("memberId").asText()).isEqualTo(editor1MemberId.toString());

        assertThat(ed2Cursor).isNotNull();
        assertThat(ed2Cursor.get("type").asText()).isEqualTo("CURSORS");
    }
}
