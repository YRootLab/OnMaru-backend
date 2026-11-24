package com.yrootlab.onmaru.web.mapcollab;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yrootlab.onmaru.identity.lifecycle.MemberLifecycleService;
import com.yrootlab.onmaru.identity.lifecycle.MemberSummary;
import com.yrootlab.onmaru.mapcollab.application.CreateMapRoomUseCase;
import com.yrootlab.onmaru.mapcollab.application.GetMapRoomDetailUseCase;
import com.yrootlab.onmaru.mapcollab.application.GetMapRoomSnapshotUseCase;
import com.yrootlab.onmaru.mapcollab.application.IssueMapRoomInviteUseCase;
import com.yrootlab.onmaru.mapcollab.application.JoinMapRoomUseCase;
import com.yrootlab.onmaru.mapcollab.application.ListMyMapRoomsUseCase;
import com.yrootlab.onmaru.mapcollab.application.RevokeMapRoomInviteUseCase;
import com.yrootlab.onmaru.mapcollab.domain.MemberRole;
import com.yrootlab.onmaru.mapcollab.domain.Pin;
import com.yrootlab.onmaru.mapcollab.domain.Room;
import com.yrootlab.onmaru.mapcollab.domain.RoomStatus;
import com.yrootlab.onmaru.mapcollab.domain.UserRoomMembership;
import com.yrootlab.onmaru.mapcollab.domain.exception.ForbiddenException;
import com.yrootlab.onmaru.mapcollab.domain.exception.InviteLimitExceededException;
import com.yrootlab.onmaru.mapcollab.domain.exception.InviteNotFoundException;
import com.yrootlab.onmaru.mapcollab.domain.exception.PinAlreadyExistsException;
import com.yrootlab.onmaru.mapcollab.domain.exception.PinLimitExceededException;
import com.yrootlab.onmaru.mapcollab.domain.exception.PinNotFoundException;
import com.yrootlab.onmaru.mapcollab.domain.exception.PlaceNotFoundException;
import com.yrootlab.onmaru.mapcollab.domain.exception.RoomExpiredException;
import com.yrootlab.onmaru.mapcollab.domain.exception.RoomFullException;
import com.yrootlab.onmaru.mapcollab.domain.exception.RoomNotFoundException;
import com.yrootlab.onmaru.mapcollab.port.RoomRepository;
import com.yrootlab.onmaru.mapcollab.runtime.RoomActor;
import com.yrootlab.onmaru.mapcollab.runtime.RoomRegistry;
import com.yrootlab.onmaru.web.mapcollab.dto.MapCollabDtos.AddMapRoomPinRequest;
import com.yrootlab.onmaru.web.mapcollab.dto.MapCollabDtos.JoinMapRoomRequest;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class MapCollabRestIntegrationTests {

    private MockMvc mockMvc;
    private ObjectMapper objectMapper;

    private MemberLifecycleService memberLifecycleService;
    private CreateMapRoomUseCase createMapRoomUseCase;
    private IssueMapRoomInviteUseCase issueMapRoomInviteUseCase;
    private RevokeMapRoomInviteUseCase revokeMapRoomInviteUseCase;
    private JoinMapRoomUseCase joinMapRoomUseCase;
    private GetMapRoomDetailUseCase getMapRoomDetailUseCase;
    private GetMapRoomSnapshotUseCase getMapRoomSnapshotUseCase;
    private ListMyMapRoomsUseCase listMyMapRoomsUseCase;
    private RoomRepository roomRepository;
    private RoomRegistry roomRegistry;

    private final Instant now = Instant.parse("2026-11-10T03:00:00Z");
    private final Clock clock = Clock.fixed(now, ZoneOffset.UTC);
    private final UUID memberId = UUID.randomUUID();
    private final String sessionToken = "valid-session-token-123";

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        memberLifecycleService = mock(MemberLifecycleService.class);
        createMapRoomUseCase = mock(CreateMapRoomUseCase.class);
        issueMapRoomInviteUseCase = mock(IssueMapRoomInviteUseCase.class);
        revokeMapRoomInviteUseCase = mock(RevokeMapRoomInviteUseCase.class);
        joinMapRoomUseCase = mock(JoinMapRoomUseCase.class);
        getMapRoomDetailUseCase = mock(GetMapRoomDetailUseCase.class);
        getMapRoomSnapshotUseCase = mock(GetMapRoomSnapshotUseCase.class);
        listMyMapRoomsUseCase = mock(ListMyMapRoomsUseCase.class);
        roomRepository = mock(RoomRepository.class);
        roomRegistry = mock(RoomRegistry.class);

        MapRoomController controller = new MapRoomController(
                memberLifecycleService,
                createMapRoomUseCase,
                issueMapRoomInviteUseCase,
                revokeMapRoomInviteUseCase,
                joinMapRoomUseCase,
                getMapRoomDetailUseCase,
                getMapRoomSnapshotUseCase,
                listMyMapRoomsUseCase,
                roomRepository,
                roomRegistry,
                clock
        );

        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new MapCollabExceptionHandler())
                .build();

        when(memberLifecycleService.currentMember(sessionToken))
                .thenReturn(Optional.of(new MemberSummary(memberId, "tester", "char1", "bg1")));
    }

    @Test
    @DisplayName("POST /api/v1/map-rooms: 방 생성 성공 201 Created")
    void createMapRoomSuccess() throws Exception {
        UUID roomId = UUID.randomUUID();
        Room room = new Room(roomId, memberId, RoomStatus.ACTIVE, now, now.plusSeconds(72 * 3600), null, null, 1L);
        var result = new CreateMapRoomUseCase.CreateRoomResult(
                room,
                MemberRole.OWNER,
                "token-abc-12345678901234567890123456789012",
                "https://onmaru.site/map?invite=token-abc-12345678901234567890123456789012",
                now.plusSeconds(72 * 3600)
        );

        when(createMapRoomUseCase.execute(eq(memberId), any())).thenReturn(result);

        mockMvc.perform(post("/api/v1/map-rooms")
                        .cookie(new Cookie("__Host-onmaru-session", sessionToken)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.schemaVersion").value("1.2"))
                .andExpect(jsonPath("$.room.id").value(roomId.toString()))
                .andExpect(jsonPath("$.room.status").value("ACTIVE"))
                .andExpect(jsonPath("$.room.role").value("OWNER"))
                .andExpect(jsonPath("$.invite.token").value(result.token()))
                .andExpect(jsonPath("$.invite.shareUrl").value(result.shareUrl()));
    }

    @Test
    @DisplayName("POST /api/v1/map-rooms: 세션 없으면 401 UNAUTHORIZED")
    void createMapRoomUnauthorized() throws Exception {
        mockMvc.perform(post("/api/v1/map-rooms"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.schemaVersion").value("1.2"))
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    @DisplayName("GET /api/v1/map-rooms/mine: 내 방 목록 200 OK")
    void listMyMapRoomsSuccess() throws Exception {
        UUID roomId = UUID.randomUUID();
        when(listMyMapRoomsUseCase.execute(eq(memberId), any())).thenReturn(List.of(
                new UserRoomMembership(roomId, RoomStatus.ACTIVE, now.plusSeconds(3600), MemberRole.OWNER, now)
        ));

        mockMvc.perform(get("/api/v1/map-rooms/mine")
                        .cookie(new Cookie("__Host-onmaru-session", sessionToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.schemaVersion").value("1.2"))
                .andExpect(jsonPath("$.rooms[0].id").value(roomId.toString()))
                .andExpect(jsonPath("$.rooms[0].role").value("OWNER"));
    }

    @Test
    @DisplayName("GET /api/v1/map-rooms/{roomId}: 방 상세 200 OK")
    void getRoomDetailSuccess() throws Exception {
        UUID roomId = UUID.randomUUID();
        Room room = new Room(roomId, memberId, RoomStatus.ACTIVE, now, now.plusSeconds(72 * 3600), null, null, 1L);
        var detailResult = new GetMapRoomDetailUseCase.RoomDetailResult(
                room,
                MemberRole.OWNER,
                List.of(new GetMapRoomDetailUseCase.MemberDetail(memberId, "tester", MemberRole.OWNER, now, true))
        );

        when(getMapRoomDetailUseCase.execute(eq(roomId), eq(memberId))).thenReturn(detailResult);

        mockMvc.perform(get("/api/v1/map-rooms/" + roomId)
                        .cookie(new Cookie("__Host-onmaru-session", sessionToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.schemaVersion").value("1.2"))
                .andExpect(jsonPath("$.room.id").value(roomId.toString()))
                .andExpect(jsonPath("$.room.role").value("OWNER"))
                .andExpect(jsonPath("$.members[0].displayName").value("tester"))
                .andExpect(jsonPath("$.members[0].online").value(true));
    }

    @Test
    @DisplayName("GET /api/v1/map-rooms/{roomId}: 멤버가 아니면 403 FORBIDDEN")
    void getRoomDetailForbidden() throws Exception {
        UUID roomId = UUID.randomUUID();
        when(getMapRoomDetailUseCase.execute(eq(roomId), eq(memberId)))
                .thenThrow(new ForbiddenException());

        mockMvc.perform(get("/api/v1/map-rooms/" + roomId)
                        .cookie(new Cookie("__Host-onmaru-session", sessionToken)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.schemaVersion").value("1.2"))
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    @DisplayName("POST /api/v1/map-rooms/{roomId}/invites: 초대 발급 성공 201 Created")
    void issueInviteSuccess() throws Exception {
        UUID roomId = UUID.randomUUID();
        var inviteResult = new IssueMapRoomInviteUseCase.IssueInviteResult(
                "token-invite-12345678901234567890123456789012",
                "https://onmaru.site/map?invite=token-invite-12345678901234567890123456789012",
                now.plusSeconds(72 * 3600)
        );

        when(issueMapRoomInviteUseCase.execute(eq(roomId), eq(memberId), any())).thenReturn(inviteResult);

        mockMvc.perform(post("/api/v1/map-rooms/" + roomId + "/invites")
                        .cookie(new Cookie("__Host-onmaru-session", sessionToken)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.schemaVersion").value("1.2"))
                .andExpect(jsonPath("$.invite.token").value(inviteResult.token()))
                .andExpect(jsonPath("$.invite.shareUrl").value(inviteResult.shareUrl()));
    }

    @Test
    @DisplayName("POST /api/v1/map-rooms/{roomId}/invites: 초대 상한(5개) 초과 시 409 INVITE_LIMIT_EXCEEDED")
    void issueInviteLimitExceeded() throws Exception {
        UUID roomId = UUID.randomUUID();
        when(issueMapRoomInviteUseCase.execute(eq(roomId), eq(memberId), any()))
                .thenThrow(new InviteLimitExceededException());

        mockMvc.perform(post("/api/v1/map-rooms/" + roomId + "/invites")
                        .cookie(new Cookie("__Host-onmaru-session", sessionToken)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.schemaVersion").value("1.2"))
                .andExpect(jsonPath("$.code").value("INVITE_LIMIT_EXCEEDED"));
    }

    @Test
    @DisplayName("DELETE /api/v1/map-rooms/{roomId}/invites/{inviteId}: 초대 철회 성공 204 No Content")
    void revokeInviteSuccess() throws Exception {
        UUID roomId = UUID.randomUUID();
        UUID inviteId = UUID.randomUUID();

        mockMvc.perform(delete("/api/v1/map-rooms/" + roomId + "/invites/" + inviteId)
                        .cookie(new Cookie("__Host-onmaru-session", sessionToken)))
                .andExpect(status().isNoContent());

        verify(revokeMapRoomInviteUseCase).execute(eq(roomId), eq(inviteId), eq(memberId), any());
    }

    @Test
    @DisplayName("POST /api/v1/map-rooms/joins: 초대 수락 입장 200 OK")
    void joinByInviteSuccess() throws Exception {
        UUID roomId = UUID.randomUUID();
        Room room = new Room(roomId, UUID.randomUUID(), RoomStatus.ACTIVE, now, now.plusSeconds(72 * 3600), null, null, 1L);
        String validToken = "bVLon_nWqa4OKw_vlakgweXAYILZ14zoCHe0fiM6tow";
        when(joinMapRoomUseCase.execute(eq(validToken), eq(memberId), any()))
                .thenReturn(new JoinMapRoomUseCase.JoinResult(room, MemberRole.MEMBER, false));

        var req = new JoinMapRoomRequest(validToken);
        mockMvc.perform(post("/api/v1/map-rooms/joins")
                        .cookie(new Cookie("__Host-onmaru-session", sessionToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.schemaVersion").value("1.2"))
                .andExpect(jsonPath("$.room.id").value(roomId.toString()))
                .andExpect(jsonPath("$.role").value("MEMBER"));
    }

    @Test
    @DisplayName("POST /api/v1/map-rooms/joins: 토큰 형식 불일치 시 400 VALIDATION_ERROR")
    void joinByInviteValidationError() throws Exception {
        var req = new JoinMapRoomRequest("short");
        mockMvc.perform(post("/api/v1/map-rooms/joins")
                        .cookie(new Cookie("__Host-onmaru-session", sessionToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.schemaVersion").value("1.2"))
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    @DisplayName("GET /api/v1/map-rooms/{roomId}/snapshot: 스냅샷 조회 200 OK")
    void getSnapshotSuccess() throws Exception {
        UUID roomId = UUID.randomUUID();
        Pin pin1 = new Pin(UUID.randomUUID(), roomId, "p-okcheon-hanok", "옥천성당", 36.3, 127.7, memberId, now, 1L, null, null, null);
        when(getMapRoomSnapshotUseCase.execute(eq(roomId), eq(memberId), any()))
                .thenReturn(new GetMapRoomSnapshotUseCase.SnapshotResult(1L, List.of(pin1)));

        mockMvc.perform(get("/api/v1/map-rooms/" + roomId + "/snapshot")
                        .cookie(new Cookie("__Host-onmaru-session", sessionToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.schemaVersion").value("1.2"))
                .andExpect(jsonPath("$.lastSeq").value(1))
                .andExpect(jsonPath("$.pins[0].placeId").value("p-okcheon-hanok"))
                .andExpect(jsonPath("$.pins[0].order").value(1));
    }

    @Test
    @DisplayName("POST /api/v1/map-rooms/{roomId}/pins: 핀 추가 성공 201 Created")
    void addPinSuccess() throws Exception {
        UUID roomId = UUID.randomUUID();
        UUID opId = UUID.randomUUID();
        Room room = new Room(roomId, memberId, RoomStatus.ACTIVE, now, now.plusSeconds(72 * 3600), null, null, 1L);
        when(roomRepository.findRoomById(roomId)).thenReturn(Optional.of(room));
        when(roomRepository.isMember(roomId, memberId)).thenReturn(true);

        RoomActor actor = mock(RoomActor.class);
        when(roomRegistry.getOrCreate(roomId)).thenReturn(actor);

        Pin pin = new Pin(UUID.randomUUID(), roomId, "p-gyeongju-bulguksa", "불국사", 35.79, 129.33, memberId, now, 2L, null, null, null);
        when(actor.addPin(eq(opId), eq("p-gyeongju-bulguksa"), eq(memberId), eq(null), any(), any()))
                .thenReturn(new RoomActor.PinAddResult(2L, pin, false));

        var req = new AddMapRoomPinRequest(opId, "p-gyeongju-bulguksa");
        mockMvc.perform(post("/api/v1/map-rooms/" + roomId + "/pins")
                        .cookie(new Cookie("__Host-onmaru-session", sessionToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.schemaVersion").value("1.2"))
                .andExpect(jsonPath("$.pin.placeId").value("p-gyeongju-bulguksa"))
                .andExpect(jsonPath("$.seq").value(2));
    }

    @Test
    @DisplayName("POST /api/v1/map-rooms/{roomId}/pins: 중복 핀 추가 시 409 PIN_ALREADY_EXISTS 와 existingPin 상세")
    void addPinDuplicatePlace() throws Exception {
        UUID roomId = UUID.randomUUID();
        UUID opId = UUID.randomUUID();
        Room room = new Room(roomId, memberId, RoomStatus.ACTIVE, now, now.plusSeconds(72 * 3600), null, null, 1L);
        when(roomRepository.findRoomById(roomId)).thenReturn(Optional.of(room));
        when(roomRepository.isMember(roomId, memberId)).thenReturn(true);

        RoomActor actor = mock(RoomActor.class);
        when(roomRegistry.getOrCreate(roomId)).thenReturn(actor);

        Pin existing = new Pin(UUID.randomUUID(), roomId, "p-okcheon-hanok", "옥천성당", 36.3, 127.7, memberId, now, 1L, null, null, null);
        when(actor.addPin(eq(opId), eq("p-okcheon-hanok"), eq(memberId), eq(null), any(), any()))
                .thenThrow(new PinAlreadyExistsException(existing));

        var req = new AddMapRoomPinRequest(opId, "p-okcheon-hanok");
        mockMvc.perform(post("/api/v1/map-rooms/" + roomId + "/pins")
                        .cookie(new Cookie("__Host-onmaru-session", sessionToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.schemaVersion").value("1.2"))
                .andExpect(jsonPath("$.code").value("PIN_ALREADY_EXISTS"))
                .andExpect(jsonPath("$.details.existingPin.placeId").value("p-okcheon-hanok"))
                .andExpect(jsonPath("$.details.existingPin.order").value(1));
    }

    @Test
    @DisplayName("DELETE /api/v1/map-rooms/{roomId}/pins/{pinId}: 핀 제거 성공 200 OK")
    void removePinSuccess() throws Exception {
        UUID roomId = UUID.randomUUID();
        UUID pinId = UUID.randomUUID();
        UUID opId = UUID.randomUUID();
        Room room = new Room(roomId, memberId, RoomStatus.ACTIVE, now, now.plusSeconds(72 * 3600), null, null, 1L);
        when(roomRepository.findRoomById(roomId)).thenReturn(Optional.of(room));
        when(roomRepository.isMember(roomId, memberId)).thenReturn(true);

        RoomActor actor = mock(RoomActor.class);
        when(roomRegistry.getOrCreate(roomId)).thenReturn(actor);

        when(actor.removePin(eq(opId), eq(pinId), eq(memberId), eq(null), any(), any()))
                .thenReturn(new RoomActor.PinRemoveResult(3L, pinId, false));

        mockMvc.perform(delete("/api/v1/map-rooms/" + roomId + "/pins/" + pinId)
                        .cookie(new Cookie("__Host-onmaru-session", sessionToken))
                        .param("opId", opId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.schemaVersion").value("1.2"))
                .andExpect(jsonPath("$.pinId").value(pinId.toString()))
                .andExpect(jsonPath("$.seq").value(3));
    }
}
