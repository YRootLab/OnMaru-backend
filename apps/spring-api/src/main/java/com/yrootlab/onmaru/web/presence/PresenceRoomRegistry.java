package com.yrootlab.onmaru.web.presence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Clock;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

final class PresenceRoomRegistry implements DisposableBean {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final DateTimeFormatter DATE_KEY = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final long SNAPSHOT_THROTTLE_MS = 1_000L;

    // roomId → clientId → SseEmitter
    private final ConcurrentHashMap<String, ConcurrentHashMap<String, SseEmitter>> rooms = new ConcurrentHashMap<>();
    // "roomId|yyyyMMdd(KST)" → Set<clientId>
    private final ConcurrentHashMap<String, Set<String>> dailyVisitors = new ConcurrentHashMap<>();
    // roomId → last snapshot broadcast timestamp (ms)
    private final ConcurrentHashMap<String, AtomicLong> lastSnapshotMs = new ConcurrentHashMap<>();
    // 서버 전체 SSE 연결 수
    private final AtomicInteger totalConnections = new AtomicInteger(0);

    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final ScheduledExecutorService pingScheduler;

    PresenceRoomRegistry(ObjectMapper objectMapper, Clock clock) {
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.pingScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            var t = new Thread(r, "presence-ping");
            t.setDaemon(true);
            return t;
        });
        pingScheduler.scheduleAtFixedRate(this::pingAll, 25, 25, TimeUnit.SECONDS);
    }

    /**
     * 새 SSE 연결 등록. maxConnections 초과 시 null 반환(503).
     * 같은 clientId 중복 연결은 이전 연결을 종료하고 교체(전역 카운트 변동 없음).
     */
    String join(String roomId, String clientId, SseEmitter emitter, int maxConnections) {
        var room = rooms.computeIfAbsent(roomId, k -> new ConcurrentHashMap<>());
        boolean isReplacement = room.containsKey(clientId);
        if (!isReplacement) {
            if (totalConnections.incrementAndGet() > maxConnections) {
                totalConnections.decrementAndGet();
                return null; // 전역 상한 초과
            }
        }
        var old = room.put(clientId, emitter);
        if (old != null && old != emitter) {
            // complete() 시 old의 onCompletion 콜백이 발화하지만,
            // leave(roomId, clientId, oldEmitter)는 room.remove(clientId, oldEmitter)로
            // 신규 emitter가 이미 들어간 entry를 건드리지 않는다.
            old.complete();
        }
        recordDailyVisitor(roomId, clientId);
        broadcastSnapshotThrottled(roomId, clientId);
        return toJson(snapshotOf(roomId));
    }

    /**
     * emitter 동일성 검사로 제거. 재접속으로 교체된 경우 신규 emitter를 건드리지 않는다.
     */
    void leave(String roomId, String clientId, SseEmitter emitter) {
        var room = rooms.get(roomId);
        if (room == null) return;
        // room.remove(key, value): value가 현재 저장된 emitter와 같을 때만 제거
        if (room.remove(clientId, emitter)) {
            totalConnections.decrementAndGet();
            broadcastSnapshotThrottled(roomId, null);
        }
    }

    void broadcastWarmth(String roomId, String senderClientId, PresenceWarmthEvent event) {
        var json = toJson(event);
        if (json == null) return;
        sendToRoom(roomId, senderClientId, json, "warmth");
    }

    private void broadcastSnapshotThrottled(String roomId, String excludeClientId) {
        var now = System.currentTimeMillis();
        var last = lastSnapshotMs.computeIfAbsent(roomId, k -> new AtomicLong(0));
        long prev = last.get();
        if (now - prev < SNAPSHOT_THROTTLE_MS) return;
        if (!last.compareAndSet(prev, now)) return;
        var json = toJson(snapshotOf(roomId));
        if (json == null) return;
        sendToRoom(roomId, excludeClientId, json, "snapshot");
    }

    private void sendToRoom(String roomId, String excludeClientId, String json, String eventName) {
        var room = rooms.get(roomId);
        if (room == null) return;
        // forEach 중 제거는 ConcurrentHashMap에서 안전하다
        room.forEach((id, em) -> {
            if (id.equals(excludeClientId)) return;
            try {
                em.send(SseEmitter.event().name(eventName).data(json));
            } catch (IOException | IllegalStateException e) {
                // 동일성 검사로 제거: 재접속으로 교체된 신규 emitter를 날리지 않는다
                if (room.remove(id, em)) totalConnections.decrementAndGet();
            }
        });
    }

    private void pingAll() {
        rooms.forEach((roomId, room) -> {
            boolean[] hadDeaths = {false};
            room.forEach((clientId, em) -> {
                try {
                    em.send(SseEmitter.event().comment("ping"));
                } catch (IOException | IllegalStateException e) {
                    if (room.remove(clientId, em)) {
                        totalConnections.decrementAndGet();
                        hadDeaths[0] = true;
                    }
                }
            });
            if (hadDeaths[0]) {
                // 스로틀 우회: TTL 정리 후 즉시 snapshot 발송
                lastSnapshotMs.computeIfAbsent(roomId, k -> new AtomicLong(0)).set(0);
                broadcastSnapshotThrottled(roomId, null);
            }
        });
    }

    private void recordDailyVisitor(String roomId, String clientId) {
        var date = clock.instant().atZone(KST).toLocalDate().format(DATE_KEY);
        var key = roomId + "|" + date;
        dailyVisitors.computeIfAbsent(key, k -> ConcurrentHashMap.newKeySet()).add(clientId);
    }

    private PresenceSnapshot snapshotOf(String roomId) {
        var room = rooms.get(roomId);
        int activeCount = room == null ? 0 : room.size();
        var date = clock.instant().atZone(KST).toLocalDate().format(DATE_KEY);
        var visitors = dailyVisitors.getOrDefault(roomId + "|" + date, Collections.emptySet());
        return new PresenceSnapshot(roomId, activeCount, visitors.size(), clock.instant().getEpochSecond());
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    @Override
    public void destroy() {
        pingScheduler.shutdownNow();
    }
}
