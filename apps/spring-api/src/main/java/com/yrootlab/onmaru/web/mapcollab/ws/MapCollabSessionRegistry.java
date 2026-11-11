package com.yrootlab.onmaru.web.mapcollab.ws;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yrootlab.onmaru.mapcollab.domain.MapCollabLimits;
import com.yrootlab.onmaru.mapcollab.domain.Pin;
import com.yrootlab.onmaru.mapcollab.domain.RoomEvent;
import com.yrootlab.onmaru.mapcollab.port.RoomEventPublisher;
import com.yrootlab.onmaru.mapcollab.port.RoomSessionTracker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@Component
public class MapCollabSessionRegistry implements RoomSessionTracker, RoomEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(MapCollabSessionRegistry.class);
    public static final CloseStatus STATUS_SLOW_CONSUMER = new CloseStatus(4029, "SLOW_CONSUMER");
    public static final CloseStatus STATUS_SUPERSEDED = new CloseStatus(4000, "SUPERSEDED");
    public static final CloseStatus STATUS_ROOM_CLOSED = new CloseStatus(4010, "ROOM_CLOSED");

    private final ObjectMapper objectMapper;
    private final ScheduledExecutorService cursorFlusher = Executors.newSingleThreadScheduledExecutor();

    // roomId -> Map<sessionId, SessionContext>
    private final Map<UUID, Map<String, SessionContext>> roomSessions = new ConcurrentHashMap<>();

    // roomId -> memberId -> List<SessionContext> (in order of connection)
    private final Map<UUID, Map<UUID, List<SessionContext>>> roomMemberSessions = new ConcurrentHashMap<>();

    // roomId -> memberId -> latest cursor
    private final Map<UUID, Map<UUID, CursorRecord>> pendingCursors = new ConcurrentHashMap<>();

    public record CursorRecord(
            UUID memberId,
            String displayName,
            Double lat,
            Double lng,
            Instant updatedAt) {
    }

    public static class SessionContext {
        final WebSocketSession session;
        final UUID roomId;
        final UUID memberId;
        final String displayName;
        final Instant connectedAt;
        final AtomicInteger pendingMessageCount = new AtomicInteger(0);

        public SessionContext(
                WebSocketSession session,
                UUID roomId,
                UUID memberId,
                String displayName,
                Instant connectedAt) {
            this.session = session;
            this.roomId = roomId;
            this.memberId = memberId;
            this.displayName = displayName;
            this.connectedAt = connectedAt;
        }

        public WebSocketSession session() { return session; }
        public UUID roomId() { return roomId; }
        public UUID memberId() { return memberId; }
        public String displayName() { return displayName; }
        public Instant connectedAt() { return connectedAt; }
    }

    public MapCollabSessionRegistry(ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
        this.cursorFlusher.scheduleWithFixedDelay(this::flushPendingCursors, 80, 80, TimeUnit.MILLISECONDS);
    }

    @Override
    public boolean isMemberOnline(UUID roomId, UUID memberId) {
        var members = roomMemberSessions.get(roomId);
        if (members == null) {
            return false;
        }
        var list = members.get(memberId);
        return list != null && !list.isEmpty();
    }

    public synchronized boolean registerSession(SessionContext context) {
        UUID roomId = context.roomId();
        UUID memberId = context.memberId();

        roomSessions.computeIfAbsent(roomId, r -> new ConcurrentHashMap<>())
                .put(context.session().getId(), context);

        var memberMap = roomMemberSessions.computeIfAbsent(roomId, r -> new ConcurrentHashMap<>());
        var sessionList = memberMap.computeIfAbsent(memberId, m -> Collections.synchronizedList(new ArrayList<>()));

        boolean firstConnection = sessionList.isEmpty();

        // Check concurrent connections per member <= 3. If 4th, terminate oldest with SUPERSEDED
        while (sessionList.size() >= MapCollabLimits.MAX_CONCURRENT_WS_PER_MEMBER) {
            SessionContext oldest = sessionList.remove(0);
            try {
                if (oldest.session().isOpen()) {
                    oldest.session().close(STATUS_SUPERSEDED);
                }
            } catch (IOException e) {
                log.warn("Error closing superseded session: {}", oldest.session().getId(), e);
            }
        }

        sessionList.add(context);
        return firstConnection;
    }

    public synchronized boolean unregisterSession(WebSocketSession session) {
        for (var entry : roomSessions.entrySet()) {
            UUID roomId = entry.getKey();
            var sessions = entry.getValue();
            SessionContext ctx = sessions.remove(session.getId());
            if (ctx != null) {
                var memberMap = roomMemberSessions.get(roomId);
                if (memberMap != null) {
                    var list = memberMap.get(ctx.memberId());
                    if (list != null) {
                        list.remove(ctx);
                        if (list.isEmpty()) {
                            memberMap.remove(ctx.memberId());
                            return true; // No remaining connections for this member
                        }
                    }
                }
                return false;
            }
        }
        return false;
    }

    public void updateCursor(UUID roomId, UUID memberId, String displayName, Double lat, Double lng) {
        pendingCursors.computeIfAbsent(roomId, r -> new ConcurrentHashMap<>())
                .put(memberId, new CursorRecord(memberId, displayName, lat, lng, Instant.now()));
    }

    private void flushPendingCursors() {
        for (var entry : pendingCursors.entrySet()) {
            UUID roomId = entry.getKey();
            var cursorsMap = entry.getValue();
            if (cursorsMap.isEmpty()) {
                continue;
            }
            List<Map<String, Object>> cursorList = new ArrayList<>();
            for (var cursor : cursorsMap.values()) {
                var item = new LinkedHashMap<String, Object>();
                item.put("memberId", cursor.memberId().toString());
                item.put("displayName", cursor.displayName());
                item.put("lat", cursor.lat());
                item.put("lng", cursor.lng());
                cursorList.add(item);
            }
            cursorsMap.clear();

            var payload = Map.of("cursors", cursorList);
            var msgMap = new LinkedHashMap<String, Object>();
            msgMap.put("v", 1);
            msgMap.put("type", "CURSORS");
            msgMap.put("payload", payload);

            try {
                String json = objectMapper.writeValueAsString(msgMap);
                broadcastText(roomId, json, null);
            } catch (Exception e) {
                log.error("Failed to broadcast cursors for room {}", roomId, e);
            }
        }
    }

    @Override
    public void publishEvent(RoomEvent event, Pin pin, String actorDisplayName) {
        var envelope = new LinkedHashMap<String, Object>();
        envelope.put("v", 1);
        envelope.put("type", "EVENT");

        var payload = new LinkedHashMap<String, Object>();
        payload.put("seq", event.seq());
        payload.put("eventType", event.eventType().name());

        if (event.actorMemberId() != null) {
            payload.put("actor", Map.of(
                    "memberId", event.actorMemberId().toString(),
                    "displayName", actorDisplayName != null ? actorDisplayName : "사용자"
            ));
        }

        if (pin != null) {
            payload.put("pin", formatPin(pin));
        } else if (event.pinId() != null) {
            payload.put("pinId", event.pinId().toString());
        }

        envelope.put("payload", payload);

        try {
            String json = objectMapper.writeValueAsString(envelope);
            broadcastText(event.roomId(), json, null);
        } catch (Exception e) {
            log.error("Failed to serialize EVENT for room {}", event.roomId(), e);
        }
    }

    @Override
    public void publishPresence(UUID roomId, UUID memberId, boolean online) {
        var envelope = new LinkedHashMap<String, Object>();
        envelope.put("v", 1);
        envelope.put("type", "PRESENCE");
        envelope.put("payload", Map.of(
                "memberId", memberId.toString(),
                "online", online
        ));
        try {
            String json = objectMapper.writeValueAsString(envelope);
            broadcastText(roomId, json, null);
        } catch (Exception e) {
            log.error("Failed to serialize PRESENCE for room {}", roomId, e);
        }
    }

    @Override
    public void closeRoomSessions(UUID roomId, int closeCode, String reason) {
        var sessions = roomSessions.remove(roomId);
        roomMemberSessions.remove(roomId);
        pendingCursors.remove(roomId);

        if (sessions != null) {
            CloseStatus status = new CloseStatus(closeCode, reason);
            for (SessionContext ctx : sessions.values()) {
                try {
                    if (ctx.session().isOpen()) {
                        ctx.session().close(status);
                    }
                } catch (IOException e) {
                    log.warn("Error closing room session: {}", ctx.session().getId(), e);
                }
            }
        }
    }

    public void sendToSession(WebSocketSession session, Object messageObject) {
        try {
            String json = objectMapper.writeValueAsString(messageObject);
            synchronized (session) {
                if (session.isOpen()) {
                    session.sendMessage(new TextMessage(json));
                }
            }
        } catch (Exception e) {
            log.error("Failed to send message to session {}", session.getId(), e);
        }
    }

    public void broadcastText(UUID roomId, String text, WebSocketSession excludeSession) {
        var sessions = roomSessions.get(roomId);
        if (sessions == null || sessions.isEmpty()) {
            return;
        }

        for (SessionContext ctx : sessions.values()) {
            if (excludeSession != null && ctx.session().getId().equals(excludeSession.getId())) {
                continue;
            }
            if (!ctx.session().isOpen()) {
                continue;
            }

            // Outbound queue capacity check (T10: capacity 256)
            int count = ctx.pendingMessageCount.incrementAndGet();
            if (count > MapCollabLimits.OUTBOX_CAPACITY) {
                log.warn("Outbound queue overflow ({}) for session {}. Closing as SLOW_CONSUMER", count, ctx.session().getId());
                try {
                    ctx.session().close(STATUS_SLOW_CONSUMER);
                } catch (IOException e) {
                    // ignore
                }
                continue;
            }

            try {
                synchronized (ctx.session()) {
                    if (ctx.session().isOpen()) {
                        ctx.session().sendMessage(new TextMessage(text));
                    }
                }
            } catch (Exception e) {
                log.warn("Error sending message to session {}", ctx.session().getId(), e);
            } finally {
                ctx.pendingMessageCount.decrementAndGet();
            }
        }
    }

    public Map<String, Object> formatPin(Pin pin) {
        var map = new LinkedHashMap<String, Object>();
        map.put("pinId", pin.pinId().toString());
        map.put("placeId", pin.placeId());
        map.put("placeName", pin.placeName());
        map.put("lat", pin.lat());
        map.put("lng", pin.lng());
        map.put("addedBy", pin.addedBy().toString());
        map.put("addedAt", pin.addedAt().toString());
        map.put("order", pin.order());
        return map;
    }
}
