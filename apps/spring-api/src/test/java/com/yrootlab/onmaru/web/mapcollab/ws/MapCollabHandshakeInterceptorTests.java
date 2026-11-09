package com.yrootlab.onmaru.web.mapcollab.ws;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yrootlab.onmaru.config.CorsOriginPolicy;
import com.yrootlab.onmaru.identity.lifecycle.MemberLifecycleService;
import com.yrootlab.onmaru.identity.lifecycle.MemberSummary;
import com.yrootlab.onmaru.mapcollab.domain.Room;
import com.yrootlab.onmaru.mapcollab.domain.RoomStatus;
import com.yrootlab.onmaru.mapcollab.port.RoomRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.web.socket.WebSocketHandler;

import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MapCollabHandshakeInterceptorTests {

    private CorsOriginPolicy corsOriginPolicy;
    private MemberLifecycleService memberLifecycleService;
    private RoomRepository roomRepository;
    private ObjectMapper objectMapper;
    private Clock clock;
    private MapCollabHandshakeInterceptor interceptor;

    private final Instant now = Instant.parse("2026-11-10T03:00:00Z");
    private final String allowedOrigin = "https://onmaru.site";
    private final String sessionToken = "session-token-xyz";
    private final UUID memberId = UUID.randomUUID();
    private final UUID roomId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        corsOriginPolicy = new CorsOriginPolicy(allowedOrigin + ",http://localhost:3000");
        memberLifecycleService = mock(MemberLifecycleService.class);
        roomRepository = mock(RoomRepository.class);
        objectMapper = new ObjectMapper();
        clock = Clock.fixed(now, ZoneOffset.UTC);

        interceptor = new MapCollabHandshakeInterceptor(
                corsOriginPolicy,
                memberLifecycleService,
                roomRepository,
                objectMapper,
                clock
        );

        when(memberLifecycleService.currentMember(sessionToken))
                .thenReturn(Optional.of(new MemberSummary(memberId, "tester", "char1", "bg1")));
    }

    private ServletServerHttpRequest createRequest(String origin, String sessionCookie, String path) {
        MockHttpServletRequest mockReq = new MockHttpServletRequest();
        if (origin != null) {
            mockReq.addHeader("Origin", origin);
        }
        if (sessionCookie != null) {
            mockReq.setCookies(new jakarta.servlet.http.Cookie("__Host-onmaru-session", sessionCookie));
        }
        mockReq.setRequestURI(path);
        return new ServletServerHttpRequest(mockReq);
    }

    @Test
    @DisplayName("T8 - Step 1: Origin이 없거나 허용되지 않으면 403 거절")
    void rejectDisallowedOrMissingOrigin() throws Exception {
        // Disallowed Origin
        var req1 = createRequest("https://evil.attacker.com", sessionToken, "/api/v1/map-rooms/" + roomId + "/ws");
        var resp1 = new ServletServerHttpResponse(new MockHttpServletResponse());
        Map<String, Object> attrs1 = new HashMap<>();

        boolean result1 = interceptor.beforeHandshake(req1, resp1, mock(WebSocketHandler.class), attrs1);
        assertThat(result1).isFalse();
        assertThat(resp1.getServletResponse().getStatus()).isEqualTo(HttpStatus.FORBIDDEN.value());

        // Missing Origin
        var req2 = createRequest(null, sessionToken, "/api/v1/map-rooms/" + roomId + "/ws");
        var resp2 = new ServletServerHttpResponse(new MockHttpServletResponse());
        Map<String, Object> attrs2 = new HashMap<>();

        boolean result2 = interceptor.beforeHandshake(req2, resp2, mock(WebSocketHandler.class), attrs2);
        assertThat(result2).isFalse();
        assertThat(resp2.getServletResponse().getStatus()).isEqualTo(HttpStatus.FORBIDDEN.value());
    }

    @Test
    @DisplayName("T8 - Step 2: 세션 쿠키가 없거나 유효하지 않으면 401 거절")
    void rejectMissingOrInvalidSession() throws Exception {
        var req = createRequest(allowedOrigin, null, "/api/v1/map-rooms/" + roomId + "/ws");
        var resp = new ServletServerHttpResponse(new MockHttpServletResponse());
        Map<String, Object> attrs = new HashMap<>();

        boolean result = interceptor.beforeHandshake(req, resp, mock(WebSocketHandler.class), attrs);
        assertThat(result).isFalse();
        assertThat(resp.getServletResponse().getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED.value());
    }

    @Test
    @DisplayName("T8 - Step 3: 방이 존재하지 않으면 404 거절")
    void rejectRoomNotFound() throws Exception {
        when(roomRepository.findRoomById(roomId)).thenReturn(Optional.empty());

        var req = createRequest(allowedOrigin, sessionToken, "/api/v1/map-rooms/" + roomId + "/ws");
        var resp = new ServletServerHttpResponse(new MockHttpServletResponse());
        Map<String, Object> attrs = new HashMap<>();

        boolean result = interceptor.beforeHandshake(req, resp, mock(WebSocketHandler.class), attrs);
        assertThat(result).isFalse();
        assertThat(resp.getServletResponse().getStatus()).isEqualTo(HttpStatus.NOT_FOUND.value());
    }

    @Test
    @DisplayName("T8 - Step 4: 방이 만료되었거나 CLOSED이면 410 거절")
    void rejectRoomExpiredOrClosed() throws Exception {
        // Closed room
        Room closedRoom = new Room(roomId, memberId, RoomStatus.CLOSED, now.minusSeconds(3600), now.plusSeconds(3600), now, memberId, 10L);
        when(roomRepository.findRoomById(roomId)).thenReturn(Optional.of(closedRoom));

        var req1 = createRequest(allowedOrigin, sessionToken, "/api/v1/map-rooms/" + roomId + "/ws");
        var resp1 = new ServletServerHttpResponse(new MockHttpServletResponse());
        Map<String, Object> attrs1 = new HashMap<>();

        boolean result1 = interceptor.beforeHandshake(req1, resp1, mock(WebSocketHandler.class), attrs1);
        assertThat(result1).isFalse();
        assertThat(resp1.getServletResponse().getStatus()).isEqualTo(HttpStatus.GONE.value());

        // Expired room
        Room expiredRoom = new Room(roomId, memberId, RoomStatus.ACTIVE, now.minusSeconds(7200), now.minusSeconds(10), null, null, 10L);
        when(roomRepository.findRoomById(roomId)).thenReturn(Optional.of(expiredRoom));

        var req2 = createRequest(allowedOrigin, sessionToken, "/api/v1/map-rooms/" + roomId + "/ws");
        var resp2 = new ServletServerHttpResponse(new MockHttpServletResponse());
        Map<String, Object> attrs2 = new HashMap<>();

        boolean result2 = interceptor.beforeHandshake(req2, resp2, mock(WebSocketHandler.class), attrs2);
        assertThat(result2).isFalse();
        assertThat(resp2.getServletResponse().getStatus()).isEqualTo(HttpStatus.GONE.value());
    }

    @Test
    @DisplayName("T8 - Step 5: 사용자가 방 멤버가 아니면 403 거절")
    void rejectNonMember() throws Exception {
        Room room = new Room(roomId, memberId, RoomStatus.ACTIVE, now, now.plusSeconds(3600), null, null, 10L);
        when(roomRepository.findRoomById(roomId)).thenReturn(Optional.of(room));
        when(roomRepository.isMember(roomId, memberId)).thenReturn(false);

        var req = createRequest(allowedOrigin, sessionToken, "/api/v1/map-rooms/" + roomId + "/ws");
        var resp = new ServletServerHttpResponse(new MockHttpServletResponse());
        Map<String, Object> attrs = new HashMap<>();

        boolean result = interceptor.beforeHandshake(req, resp, mock(WebSocketHandler.class), attrs);
        assertThat(result).isFalse();
        assertThat(resp.getServletResponse().getStatus()).isEqualTo(HttpStatus.FORBIDDEN.value());
    }

    @Test
    @DisplayName("T8 - Step 1~5 통과: 핸드셰이크 성공 및 세션 속성 주입")
    void handshakeSuccess() throws Exception {
        Room room = new Room(roomId, memberId, RoomStatus.ACTIVE, now, now.plusSeconds(3600), null, null, 10L);
        when(roomRepository.findRoomById(roomId)).thenReturn(Optional.of(room));
        when(roomRepository.isMember(roomId, memberId)).thenReturn(true);

        MockHttpServletRequest mockReq = new MockHttpServletRequest();
        mockReq.addHeader("Origin", allowedOrigin);
        mockReq.setCookies(new jakarta.servlet.http.Cookie("__Host-onmaru-session", sessionToken));
        mockReq.setRequestURI("/api/v1/map-rooms/" + roomId + "/ws");
        mockReq.setQueryString("lastSeq=5");

        var req = new ServletServerHttpRequest(mockReq);
        var resp = new ServletServerHttpResponse(new MockHttpServletResponse());
        Map<String, Object> attrs = new HashMap<>();

        boolean result = interceptor.beforeHandshake(req, resp, mock(WebSocketHandler.class), attrs);
        assertThat(result).isTrue();
        assertThat(attrs.get("roomId")).isEqualTo(roomId);
        assertThat(attrs.get("memberId")).isEqualTo(memberId);
        assertThat(attrs.get("displayName")).isEqualTo("tester");
        assertThat(attrs.get("lastSeq")).isEqualTo(5L);
    }
}
