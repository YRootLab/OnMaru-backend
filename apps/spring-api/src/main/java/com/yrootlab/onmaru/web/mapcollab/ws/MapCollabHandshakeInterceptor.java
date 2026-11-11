package com.yrootlab.onmaru.web.mapcollab.ws;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yrootlab.onmaru.config.CorsOriginPolicy;
import com.yrootlab.onmaru.identity.lifecycle.MemberLifecycleService;
import com.yrootlab.onmaru.identity.lifecycle.MemberSummary;
import com.yrootlab.onmaru.mapcollab.domain.Room;
import com.yrootlab.onmaru.mapcollab.domain.RoomStatus;
import com.yrootlab.onmaru.mapcollab.port.RoomRepository;
import com.yrootlab.onmaru.web.common.error.ApiErrorResponse;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.io.IOException;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class MapCollabHandshakeInterceptor implements HandshakeInterceptor {

    private static final Logger log = LoggerFactory.getLogger(MapCollabHandshakeInterceptor.class);
    private static final String SESSION_COOKIE = "__Host-onmaru-session";
    private static final Pattern ROOM_ID_PATTERN = Pattern.compile("/map-rooms/([0-9a-fA-F-]+)/ws");

    private final CorsOriginPolicy corsOriginPolicy;
    private final MemberLifecycleService memberLifecycleService;
    private final RoomRepository roomRepository;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public MapCollabHandshakeInterceptor(
            CorsOriginPolicy corsOriginPolicy,
            MemberLifecycleService memberLifecycleService,
            RoomRepository roomRepository,
            ObjectMapper objectMapper,
            Clock clock) {
        this.corsOriginPolicy = Objects.requireNonNull(corsOriginPolicy);
        this.memberLifecycleService = Objects.requireNonNull(memberLifecycleService);
        this.roomRepository = Objects.requireNonNull(roomRepository);
        this.objectMapper = Objects.requireNonNull(objectMapper);
        this.clock = Objects.requireNonNull(clock);
    }

    @Override
    public boolean beforeHandshake(
            ServerHttpRequest request,
            ServerHttpResponse response,
            WebSocketHandler wsHandler,
            Map<String, Object> attributes) throws Exception {

        String requestId = extractRequestId(request);

        // Step 1: Origin check against CorsOriginPolicy
        String origin = request.getHeaders().getOrigin();
        if (origin == null || origin.isBlank() || !corsOriginPolicy.allows(origin)) {
            log.warn("WS Handshake rejected: origin not allowed: {}", origin);
            writeError(response, HttpStatus.FORBIDDEN, "FORBIDDEN", "Origin not allowed", requestId);
            return false;
        }

        // Step 2: Session cookie check
        String sessionToken = extractSessionToken(request);
        if (sessionToken == null || sessionToken.isBlank()) {
            writeError(response, HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "Login session required", requestId);
            return false;
        }

        Optional<MemberSummary> memberOpt = memberLifecycleService.currentMember(sessionToken);
        if (memberOpt.isEmpty()) {
            writeError(response, HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "Invalid or expired session", requestId);
            return false;
        }
        MemberSummary member = memberOpt.get();

        // Extract roomId from URI path
        UUID roomId = extractRoomId(request.getURI());
        if (roomId == null) {
            writeError(response, HttpStatus.NOT_FOUND, "ROOM_NOT_FOUND", "room not found", requestId);
            return false;
        }

        // Step 3: Room existence check
        Optional<Room> roomOpt = roomRepository.findRoomById(roomId);
        if (roomOpt.isEmpty()) {
            writeError(response, HttpStatus.NOT_FOUND, "ROOM_NOT_FOUND", "room not found", requestId);
            return false;
        }
        Room room = roomOpt.get();

        // Step 4: Room active and not expired check
        Instant now = clock.instant();
        if (room.status() == RoomStatus.CLOSED) {
            writeError(response, HttpStatus.GONE, "ROOM_CLOSED", "room closed", requestId);
            return false;
        }
        if (room.isExpired(now)) {
            writeError(response, HttpStatus.GONE, "ROOM_EXPIRED", "room expired", requestId);
            return false;
        }

        // Step 5: Membership check
        if (!roomRepository.isMember(roomId, member.id())) {
            writeError(response, HttpStatus.FORBIDDEN, "FORBIDDEN", "Not a member of this room", requestId);
            return false;
        }

        // Parse optional lastSeq from query
        Long lastSeq = extractLastSeq(request.getURI());

        // Attributes for WebSocketHandler
        attributes.put("roomId", roomId);
        attributes.put("memberId", member.id());
        attributes.put("displayName", member.displayName() != null ? member.displayName() : "사용자");
        attributes.put("roomExpiresAt", room.expiresAt());
        attributes.put("lastSeq", lastSeq);
        attributes.put("requestId", requestId);

        return true;
    }

    @Override
    public void afterHandshake(
            ServerHttpRequest request,
            ServerHttpResponse response,
            WebSocketHandler wsHandler,
            Exception exception) {
    }

    private void writeError(
            ServerHttpResponse response,
            HttpStatus status,
            String code,
            String message,
            String requestId) throws IOException {
        response.setStatusCode(status);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        var body = new ApiErrorResponse("1.2", code, message, requestId, Map.of());
        byte[] bytes = objectMapper.writeValueAsBytes(body);
        response.getBody().write(bytes);
    }

    private UUID extractRoomId(URI uri) {
        if (uri == null) return null;
        String path = uri.getPath();
        Matcher matcher = ROOM_ID_PATTERN.matcher(path);
        if (matcher.find()) {
            try {
                return UUID.fromString(matcher.group(1));
            } catch (IllegalArgumentException e) {
                return null;
            }
        }
        return null;
    }

    private Long extractLastSeq(URI uri) {
        if (uri == null || uri.getQuery() == null) return null;
        String query = uri.getQuery();
        for (String param : query.split("&")) {
            String[] kv = param.split("=");
            if (kv.length == 2 && "lastSeq".equals(kv[0])) {
                try {
                    return Long.parseLong(kv[1]);
                } catch (NumberFormatException e) {
                    return null;
                }
            }
        }
        return null;
    }

    private String extractSessionToken(ServerHttpRequest request) {
        if (request instanceof ServletServerHttpRequest servletRequest) {
            HttpServletRequest httpServletRequest = servletRequest.getServletRequest();
            Cookie[] cookies = httpServletRequest.getCookies();
            if (cookies != null) {
                for (Cookie cookie : cookies) {
                    if (SESSION_COOKIE.equals(cookie.getName())) {
                        return cookie.getValue();
                    }
                }
            }
        }
        // Fallback to Cookie header
        String cookieHeader = request.getHeaders().getFirst(HttpHeaders.COOKIE);
        if (cookieHeader != null) {
            for (String part : cookieHeader.split(";")) {
                String trimmed = part.trim();
                if (trimmed.startsWith(SESSION_COOKIE + "=")) {
                    return trimmed.substring((SESSION_COOKIE + "=").length());
                }
            }
        }
        return null;
    }

    private String extractRequestId(ServerHttpRequest request) {
        String fromHeader = request.getHeaders().getFirst("X-Request-Id");
        return fromHeader != null && !fromHeader.isBlank() ? fromHeader : UUID.randomUUID().toString();
    }
}
