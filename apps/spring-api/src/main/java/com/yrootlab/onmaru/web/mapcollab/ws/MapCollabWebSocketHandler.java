package com.yrootlab.onmaru.web.mapcollab.ws;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yrootlab.onmaru.mapcollab.domain.Member;
import com.yrootlab.onmaru.mapcollab.domain.Pin;
import com.yrootlab.onmaru.mapcollab.domain.Room;
import com.yrootlab.onmaru.mapcollab.domain.RoomEvent;
import com.yrootlab.onmaru.mapcollab.domain.RoomEventType;
import com.yrootlab.onmaru.mapcollab.domain.exception.MapCollabException;
import com.yrootlab.onmaru.mapcollab.domain.exception.PinAlreadyExistsException;
import com.yrootlab.onmaru.mapcollab.port.MemberProfilePort;
import com.yrootlab.onmaru.mapcollab.port.RoomRepository;
import com.yrootlab.onmaru.mapcollab.runtime.RoomActor;
import com.yrootlab.onmaru.mapcollab.runtime.RoomRegistry;
import com.yrootlab.onmaru.persistence.mapcollab.RoomSessionStore;
import com.yrootlab.onmaru.persistence.mapcollab.JdbcRoomSessionStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.PingMessage;
import org.springframework.web.socket.PongMessage;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Component
public class MapCollabWebSocketHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(MapCollabWebSocketHandler.class);
    private static final int MAX_MESSAGE_SIZE = 4096;
    private static final Pattern PLACE_ID_PATTERN = Pattern.compile("^p-[a-z0-9]+(-[a-z0-9]+)*$");

    private final ObjectMapper objectMapper;
    private final RoomRepository roomRepository;
    private final RoomRegistry roomRegistry;
    private final MemberProfilePort memberProfilePort;
    private final MapCollabSessionRegistry sessionRegistry;
    private final RoomSessionStore roomSessionStore;
    private final Clock clock;

    private final ScheduledExecutorService heartbeatExecutor = Executors.newSingleThreadScheduledExecutor();
    private final Map<String, Instant> lastPongTimes = new ConcurrentHashMap<>();

    public MapCollabWebSocketHandler(
            ObjectMapper objectMapper,
            RoomRepository roomRepository,
            RoomRegistry roomRegistry,
            MemberProfilePort memberProfilePort,
            MapCollabSessionRegistry sessionRegistry,
            RoomSessionStore roomSessionStore,
            Clock clock) {
        this.objectMapper = Objects.requireNonNull(objectMapper);
        this.roomRepository = Objects.requireNonNull(roomRepository);
        this.roomRegistry = Objects.requireNonNull(roomRegistry);
        this.memberProfilePort = Objects.requireNonNull(memberProfilePort);
        this.sessionRegistry = Objects.requireNonNull(sessionRegistry);
        this.roomSessionStore = Objects.requireNonNull(roomSessionStore);
        this.clock = Objects.requireNonNull(clock);

        this.heartbeatExecutor.scheduleWithFixedDelay(this::checkHeartbeats, 25, 25, TimeUnit.SECONDS);
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        UUID roomId = (UUID) session.getAttributes().get("roomId");
        UUID memberId = (UUID) session.getAttributes().get("memberId");
        String displayName = (String) session.getAttributes().get("displayName");
        Long lastSeq = (Long) session.getAttributes().get("lastSeq");

        UUID dbSessionId = UUID.randomUUID();
        session.getAttributes().put("dbSessionId", dbSessionId);
        Instant now = clock.instant();
        lastPongTimes.put(session.getId(), now);

        roomSessionStore.insertSession(dbSessionId, roomId, memberId, "spring-1", now);

        var context = new MapCollabSessionRegistry.SessionContext(
                session, roomId, memberId, displayName, now
        );
        boolean firstConnection = sessionRegistry.registerSession(context);
        if (firstConnection) {
            sessionRegistry.publishPresence(roomId, memberId, true);
        }

        // Welcome or Resume
        if (lastSeq == null) {
            sendWelcome(session, roomId, memberId, displayName);
        } else {
            replayOrResync(session, roomId, lastSeq);
        }
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        if (message.getPayloadLength() > MAX_MESSAGE_SIZE) {
            log.warn("Message size {} exceeded maximum allowed {}", message.getPayloadLength(), MAX_MESSAGE_SIZE);
            sendError(session, null, "VALIDATION_ERROR", "message size exceeded 4KB", null);
            session.close(new CloseStatus(4000, "MESSAGE_TOO_LARGE"));
            return;
        }

        UUID roomId = (UUID) session.getAttributes().get("roomId");
        UUID memberId = (UUID) session.getAttributes().get("memberId");
        String displayName = (String) session.getAttributes().get("displayName");
        Instant roomExpiresAt = (Instant) session.getAttributes().get("roomExpiresAt");
        UUID dbSessionId = (UUID) session.getAttributes().get("dbSessionId");

        JsonNode root;
        try {
            root = objectMapper.readTree(message.getPayload());
        } catch (Exception e) {
            sendError(session, null, "VALIDATION_ERROR", "invalid json", null);
            session.close(new CloseStatus(4000, "INVALID_JSON"));
            return;
        }

        JsonNode vNode = root.get("v");
        JsonNode typeNode = root.get("type");
        if (vNode == null || vNode.asInt() != 1 || typeNode == null || !typeNode.isTextual()) {
            sendError(session, null, "VALIDATION_ERROR", "unsupported version or missing type", null);
            session.close(new CloseStatus(4000, "INVALID_ENVELOPE"));
            return;
        }

        String type = typeNode.asText();
        String opIdStr = root.has("opId") && root.get("opId").isTextual() ? root.get("opId").asText() : null;
        UUID opId = null;
        if (opIdStr != null) {
            try {
                opId = UUID.fromString(opIdStr);
            } catch (IllegalArgumentException e) {
                sendError(session, null, "VALIDATION_ERROR", "invalid opId uuid format", null);
                return;
            }
        }

        JsonNode payload = root.get("payload");
        Instant now = clock.instant();

        switch (type) {
            case "PIN_ADD" -> {
                if (opId == null) {
                    sendError(session, null, "VALIDATION_ERROR", "opId is required for PIN_ADD", null);
                    return;
                }
                if (payload == null || !payload.has("placeId") || !payload.get("placeId").isTextual()) {
                    sendError(session, opId, "VALIDATION_ERROR", "placeId is required", null);
                    return;
                }
                String placeId = payload.get("placeId").asText();
                if (!PLACE_ID_PATTERN.matcher(placeId).matches()) {
                    sendError(session, opId, "VALIDATION_ERROR", "placeId must match catalog place format", null);
                    return;
                }

                RoomActor actor = roomRegistry.getOrCreate(roomId);
                try {
                    var result = actor.addPin(opId, placeId, memberId, dbSessionId, now, roomExpiresAt);
                    sendAck(session, opId, result.seq(), result.pin(), null);
                } catch (MapCollabException e) {
                    Pin existingPin = (e instanceof PinAlreadyExistsException pae) ? pae.getExistingPin() : null;
                    sendError(session, opId, e.getCode(), e.getMessage(), existingPin);
                } catch (Exception e) {
                    log.error("Error adding pin in room {}", roomId, e);
                    sendError(session, opId, "INTERNAL_ERROR", "internal error", null);
                }
            }

            case "PIN_REMOVE" -> {
                if (opId == null) {
                    sendError(session, null, "VALIDATION_ERROR", "opId is required for PIN_REMOVE", null);
                    return;
                }
                if (payload == null || !payload.has("pinId") || !payload.get("pinId").isTextual()) {
                    sendError(session, opId, "VALIDATION_ERROR", "pinId is required", null);
                    return;
                }
                UUID pinId;
                try {
                    pinId = UUID.fromString(payload.get("pinId").asText());
                } catch (IllegalArgumentException e) {
                    sendError(session, opId, "VALIDATION_ERROR", "invalid pinId uuid", null);
                    return;
                }

                RoomActor actor = roomRegistry.getOrCreate(roomId);
                try {
                    var result = actor.removePin(opId, pinId, memberId, dbSessionId, now, roomExpiresAt);
                    sendAck(session, opId, result.seq(), null, result.pinId());
                } catch (MapCollabException e) {
                    sendError(session, opId, e.getCode(), e.getMessage(), null);
                } catch (Exception e) {
                    log.error("Error removing pin in room {}", roomId, e);
                    sendError(session, opId, "INTERNAL_ERROR", "internal error", null);
                }
            }

            case "CURSOR" -> {
                if (payload == null || !payload.has("lat") || !payload.has("lng")) {
                    return;
                }
                JsonNode latNode = payload.get("lat");
                JsonNode lngNode = payload.get("lng");
                Double lat = (latNode != null && !latNode.isNull() && latNode.isNumber()) ? latNode.asDouble() : null;
                Double lng = (lngNode != null && !lngNode.isNull() && lngNode.isNumber()) ? lngNode.asDouble() : null;

                if (lat != null && (lat < -90 || lat > 90)) {
                    sendError(session, null, "VALIDATION_ERROR", "lat out of range", null);
                    return;
                }
                if (lng != null && (lng < -180 || lng > 180)) {
                    sendError(session, null, "VALIDATION_ERROR", "lng out of range", null);
                    return;
                }

                sessionRegistry.updateCursor(roomId, memberId, displayName, lat, lng);
            }

            case "RESUME" -> {
                if (payload == null || !payload.has("lastSeq") || !payload.get("lastSeq").isNumber()) {
                    sendError(session, null, "VALIDATION_ERROR", "lastSeq must be a number", null);
                    return;
                }
                long resumeLastSeq = payload.get("lastSeq").asLong();
                replayOrResync(session, roomId, resumeLastSeq);
            }

            default -> {
                log.warn("Unknown message type: {}", type);
                sendError(session, opId, "VALIDATION_ERROR", "unknown message type: " + type, null);
            }
        }
    }

    @Override
    protected void handlePongMessage(WebSocketSession session, PongMessage message) {
        lastPongTimes.put(session.getId(), clock.instant());
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
        UUID dbSessionId = (UUID) session.getAttributes().get("dbSessionId");
        UUID roomId = (UUID) session.getAttributes().get("roomId");
        UUID memberId = (UUID) session.getAttributes().get("memberId");

        lastPongTimes.remove(session.getId());

        String endReason = mapCloseStatus(status);
        if (dbSessionId != null) {
            roomSessionStore.updateDisconnected(dbSessionId, clock.instant(), endReason);
        }

        boolean lastConnection = sessionRegistry.unregisterSession(session);
        if (lastConnection && roomId != null && memberId != null) {
            sessionRegistry.publishPresence(roomId, memberId, false);
        }
    }

    private void checkHeartbeats() {
        Instant now = clock.instant();
        for (var entry : lastPongTimes.entrySet()) {
            String sessionId = entry.getKey();
            Instant lastPong = entry.getValue();

            // Ping every 25s, close if pong missing for > 60s
            if (now.isAfter(lastPong.plusSeconds(60))) {
                log.warn("Heartbeat timeout for session {}", sessionId);
                // Close session
                // We'll let Spring handle close or notify
            }
        }
    }

    private void sendWelcome(WebSocketSession session, UUID roomId, UUID memberId, String displayName) {
        Room room = roomRepository.findRoomById(roomId).orElse(null);
        if (room == null) return;

        RoomActor actor = roomRegistry.getOrCreate(roomId);
        List<Pin> activePins = actor.getActivePins();
        long lastSeq = actor.getLastEventSeq();

        List<Member> members = roomRepository.findMembersByRoomId(roomId);
        Set<UUID> memberIds = members.stream().map(Member::memberId).collect(Collectors.toSet());
        Map<UUID, String> displayNames = memberProfilePort.findDisplayNames(memberIds);

        List<Map<String, String>> memberList = new ArrayList<>();
        for (Member m : members) {
            String name = displayNames.getOrDefault(m.memberId(), m.displayName());
            if (name.isBlank()) name = "사용자";
            memberList.add(Map.of("memberId", m.memberId().toString(), "displayName", name));
        }

        List<Map<String, Object>> pinList = new ArrayList<>();
        for (Pin p : activePins) {
            pinList.add(sessionRegistry.formatPin(p));
        }

        var payload = new LinkedHashMap<String, Object>();
        payload.put("me", Map.of("memberId", memberId.toString(), "displayName", displayName));
        payload.put("room", Map.of(
                "id", roomId.toString(),
                "status", room.status().name(),
                "expiresAt", room.expiresAt().toString()
        ));
        payload.put("members", memberList);
        payload.put("pins", pinList);
        payload.put("lastSeq", lastSeq);

        var welcome = new LinkedHashMap<String, Object>();
        welcome.put("v", 1);
        welcome.put("type", "WELCOME");
        welcome.put("payload", payload);

        sessionRegistry.sendToSession(session, welcome);
    }

    private void replayOrResync(WebSocketSession session, UUID roomId, long lastSeq) {
        RoomActor actor = roomRegistry.getOrCreate(roomId);
        long currentSeq = actor.getLastEventSeq();

        if (lastSeq < 0 || lastSeq > currentSeq) {
            sendResync(session, "GAP");
            return;
        }

        List<RoomEvent> events = roomRepository.findEventsAfter(roomId, lastSeq);
        // Verify gap continuity
        long expectedSeq = lastSeq + 1;
        for (RoomEvent event : events) {
            if (event.seq() != expectedSeq) {
                sendResync(session, "GAP");
                return;
            }
            expectedSeq++;
        }

        // Replay each event
        for (RoomEvent event : events) {
            if (isCollabWsEvent(event.eventType())) {
                sendEvent(session, event);
            }
        }
    }

    private boolean isCollabWsEvent(RoomEventType type) {
        return type == RoomEventType.PIN_ADDED
                || type == RoomEventType.PIN_REMOVED
                || type == RoomEventType.MEMBER_JOINED
                || type == RoomEventType.ROOM_CLOSED;
    }

    private void sendEvent(WebSocketSession session, RoomEvent event) {
        var envelope = new LinkedHashMap<String, Object>();
        envelope.put("v", 1);
        envelope.put("type", "EVENT");

        var payload = new LinkedHashMap<String, Object>();
        payload.put("seq", event.seq());
        payload.put("eventType", event.eventType().name());

        if (event.actorMemberId() != null) {
            String name = memberProfilePort.findDisplayName(event.actorMemberId());
            payload.put("actor", Map.of(
                    "memberId", event.actorMemberId().toString(),
                    "displayName", name != null ? name : "사용자"
            ));
        }

        if (event.pinId() != null) {
            if (event.eventType() == RoomEventType.PIN_ADDED) {
                Pin pin = roomRepository.findActivePinById(event.roomId(), event.pinId()).orElse(null);
                if (pin != null) {
                    payload.put("pin", sessionRegistry.formatPin(pin));
                }
            } else if (event.eventType() == RoomEventType.PIN_REMOVED) {
                payload.put("pinId", event.pinId().toString());
            }
        }

        envelope.put("payload", payload);
        sessionRegistry.sendToSession(session, envelope);
    }

    private void sendResync(WebSocketSession session, String reason) {
        var resync = new LinkedHashMap<String, Object>();
        resync.put("v", 1);
        resync.put("type", "RESYNC_REQUIRED");
        resync.put("payload", Map.of("reason", reason));
        sessionRegistry.sendToSession(session, resync);
    }

    private void sendAck(WebSocketSession session, UUID opId, long seq, Pin pin, UUID pinId) {
        var ack = new LinkedHashMap<String, Object>();
        ack.put("v", 1);
        ack.put("type", "ACK");
        ack.put("opId", opId.toString());

        var payload = new LinkedHashMap<String, Object>();
        payload.put("seq", seq);
        if (pin != null) {
            payload.put("pin", sessionRegistry.formatPin(pin));
        }
        if (pinId != null) {
            payload.put("pinId", pinId.toString());
        }

        ack.put("payload", payload);
        sessionRegistry.sendToSession(session, ack);
    }

    private void sendError(WebSocketSession session, UUID opId, String code, String message, Pin existingPin) {
        var err = new LinkedHashMap<String, Object>();
        err.put("v", 1);
        err.put("type", "ERROR");
        if (opId != null) {
            err.put("opId", opId.toString());
        }

        var payload = new LinkedHashMap<String, Object>();
        payload.put("code", code);
        payload.put("message", message);
        if (existingPin != null) {
            payload.put("existingPin", sessionRegistry.formatPin(existingPin));
        }

        err.put("payload", payload);
        sessionRegistry.sendToSession(session, err);
    }

    private String mapCloseStatus(CloseStatus status) {
        if (status == null) return "CLIENT_CLOSED";
        int code = status.getCode();
        return switch (code) {
            case 4010 -> "ROOM_CLOSED";
            case 4029 -> "SLOW_CONSUMER";
            case 4000 -> status.getReason() != null && status.getReason().contains("SUPERSEDED") ? "SUPERSEDED" : "CLIENT_CLOSED";
            case 4001 -> "AUTH_EXPIRED";
            default -> "CLIENT_CLOSED";
        };
    }
}
