package com.yrootlab.onmaru.mapcollab.runtime;

import com.yrootlab.onmaru.mapcollab.domain.MapCollabLimits;
import com.yrootlab.onmaru.mapcollab.domain.Pin;
import com.yrootlab.onmaru.mapcollab.domain.Room;
import com.yrootlab.onmaru.mapcollab.domain.RoomEvent;
import com.yrootlab.onmaru.mapcollab.domain.RoomEventType;
import com.yrootlab.onmaru.mapcollab.domain.exception.OpConflictException;
import com.yrootlab.onmaru.mapcollab.domain.exception.PinAlreadyExistsException;
import com.yrootlab.onmaru.mapcollab.domain.exception.PinLimitExceededException;
import com.yrootlab.onmaru.mapcollab.domain.exception.PinNotFoundException;
import com.yrootlab.onmaru.mapcollab.domain.exception.PlaceNotFoundException;
import com.yrootlab.onmaru.mapcollab.domain.exception.RoomClosedException;
import com.yrootlab.onmaru.mapcollab.domain.exception.RoomExpiredException;
import com.yrootlab.onmaru.mapcollab.port.CatalogPlaceInfo;
import com.yrootlab.onmaru.mapcollab.port.MemberProfilePort;
import com.yrootlab.onmaru.mapcollab.port.PlaceCatalogPort;
import com.yrootlab.onmaru.mapcollab.port.RoomEventPublisher;
import com.yrootlab.onmaru.mapcollab.port.RoomRepository;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class RoomActor {

    private final UUID roomId;
    private final RoomRepository roomRepository;
    private final PlaceCatalogPort placeCatalogPort;
    private final MemberProfilePort memberProfilePort;
    private final RoomEventPublisher roomEventPublisher;

    private long lastEventSeq;
    private final Map<UUID, Pin> activePinsById = new LinkedHashMap<>();
    private final Map<String, Pin> activePinsByPlaceId = new LinkedHashMap<>();
    private final Map<UUID, ProcessedOpResult> processedOps = new ConcurrentHashMap<>();

    public record ProcessedOpResult(
            long seq,
            Pin pin,
            UUID removedPinId,
            String placeId) {
    }

    public record PinAddResult(
            long seq,
            Pin pin,
            boolean isReplay) {
    }

    public record PinRemoveResult(
            long seq,
            UUID pinId,
            boolean isReplay) {
    }

    public RoomActor(
            UUID roomId,
            long initialLastEventSeq,
            List<Pin> initialActivePins,
            RoomRepository roomRepository,
            PlaceCatalogPort placeCatalogPort,
            MemberProfilePort memberProfilePort,
            RoomEventPublisher roomEventPublisher) {
        this.roomId = Objects.requireNonNull(roomId, "roomId must not be null");
        this.lastEventSeq = initialLastEventSeq;
        this.roomRepository = Objects.requireNonNull(roomRepository, "roomRepository must not be null");
        this.placeCatalogPort = Objects.requireNonNull(placeCatalogPort, "placeCatalogPort must not be null");
        this.memberProfilePort = Objects.requireNonNull(memberProfilePort, "memberProfilePort must not be null");
        this.roomEventPublisher = Objects.requireNonNull(roomEventPublisher, "roomEventPublisher must not be null");

        if (initialActivePins != null) {
            for (Pin pin : initialActivePins) {
                if (pin.isActive()) {
                    this.activePinsById.put(pin.pinId(), pin);
                    this.activePinsByPlaceId.put(pin.placeId(), pin);
                }
            }
        }
    }

    public static RoomActor restore(
            UUID roomId,
            Room room,
            RoomRepository roomRepository,
            PlaceCatalogPort placeCatalogPort,
            MemberProfilePort memberProfilePort,
            RoomEventPublisher roomEventPublisher) {
        List<Pin> activePins = roomRepository.findActivePins(roomId);
        return new RoomActor(
                roomId,
                room.lastEventSeq(),
                activePins,
                roomRepository,
                placeCatalogPort,
                memberProfilePort,
                roomEventPublisher
        );
    }

    public synchronized PinAddResult addPin(
            UUID clientOpId,
            String placeId,
            UUID actorMemberId,
            UUID sessionId,
            Instant now,
            Instant roomExpiresAt) {
        if (!now.isBefore(roomExpiresAt)) {
            throw new RoomExpiredException();
        }

        // 1. Idempotency check in memory
        ProcessedOpResult cached = processedOps.get(clientOpId);
        if (cached != null) {
            if (cached.pin() != null && Objects.equals(cached.placeId(), placeId)) {
                return new PinAddResult(cached.seq(), cached.pin(), true);
            }
            throw new OpConflictException();
        }

        // 1-1. Idempotency check in DB (if actor was restarted or evicted)
        var dbEventOpt = roomRepository.findEventByOpId(roomId, clientOpId);
        if (dbEventOpt.isPresent()) {
            RoomEvent dbEvent = dbEventOpt.get();
            if (dbEvent.eventType() == RoomEventType.PIN_ADDED) {
                Pin pin = activePinsById.get(dbEvent.pinId());
                if (pin == null) {
                    pin = roomRepository.findActivePinById(roomId, dbEvent.pinId()).orElse(null);
                }
                if (pin != null && Objects.equals(pin.placeId(), placeId)) {
                    processedOps.put(clientOpId, new ProcessedOpResult(dbEvent.seq(), pin, null, placeId));
                    return new PinAddResult(dbEvent.seq(), pin, true);
                }
            }
            throw new OpConflictException();
        }

        // 2. Validate place from catalog (D9)
        CatalogPlaceInfo place = placeCatalogPort.findPlaceByPublicId(placeId)
                .orElseThrow(() -> new PlaceNotFoundException(placeId));

        // 3. Validate duplicate place (D1)
        Pin existingPin = activePinsByPlaceId.get(placeId);
        if (existingPin != null) {
            throw new PinAlreadyExistsException(existingPin);
        }

        // 4. Validate pin limit
        if (activePinsById.size() >= MapCollabLimits.MAX_ACTIVE_PINS_PER_ROOM) {
            throw new PinLimitExceededException();
        }

        // 5. DB write in transaction
        long nextSeq = lastEventSeq + 1;
        UUID pinId = UUID.randomUUID();
        Pin newPin = new Pin(
                pinId,
                roomId,
                placeId,
                place.name(),
                place.latitude(),
                place.longitude(),
                actorMemberId,
                now,
                nextSeq
        );
        String payloadJson = "{\"pinId\":\"" + pinId + "\",\"placeId\":\"" + placeId + "\"}";
        RoomEvent event = new RoomEvent(
                roomId,
                nextSeq,
                RoomEventType.PIN_ADDED,
                actorMemberId,
                sessionId,
                clientOpId,
                pinId,
                payloadJson,
                now
        );

        roomRepository.addPin(newPin, event, nextSeq);

        // 6. DB write succeeded -> apply to in-memory state
        this.lastEventSeq = nextSeq;
        this.activePinsById.put(pinId, newPin);
        this.activePinsByPlaceId.put(placeId, newPin);
        this.processedOps.put(clientOpId, new ProcessedOpResult(nextSeq, newPin, null, placeId));

        // 7. Broadcast
        String actorDisplayName = memberProfilePort.findDisplayName(actorMemberId);
        roomEventPublisher.publishEvent(event, newPin, actorDisplayName);

        return new PinAddResult(nextSeq, newPin, false);
    }

    public synchronized PinRemoveResult removePin(
            UUID clientOpId,
            UUID pinId,
            UUID actorMemberId,
            UUID sessionId,
            Instant now,
            Instant roomExpiresAt) {
        if (!now.isBefore(roomExpiresAt)) {
            throw new RoomExpiredException();
        }

        // 1. Idempotency check in memory
        ProcessedOpResult cached = processedOps.get(clientOpId);
        if (cached != null) {
            if (cached.removedPinId() != null && Objects.equals(cached.removedPinId(), pinId)) {
                return new PinRemoveResult(cached.seq(), cached.removedPinId(), true);
            }
            throw new OpConflictException();
        }

        // 1-1. Idempotency check in DB
        var dbEventOpt = roomRepository.findEventByOpId(roomId, clientOpId);
        if (dbEventOpt.isPresent()) {
            RoomEvent dbEvent = dbEventOpt.get();
            if (dbEvent.eventType() == RoomEventType.PIN_REMOVED) {
                if (Objects.equals(dbEvent.pinId(), pinId)) {
                    processedOps.put(clientOpId, new ProcessedOpResult(dbEvent.seq(), null, pinId, null));
                    return new PinRemoveResult(dbEvent.seq(), pinId, true);
                }
            }
            throw new OpConflictException();
        }

        // 2. Validate pin exists and is active
        Pin pin = activePinsById.get(pinId);
        if (pin == null) {
            throw new PinNotFoundException();
        }

        // 3. DB write in transaction
        long nextSeq = lastEventSeq + 1;
        String payloadJson = "{\"pinId\":\"" + pinId + "\"}";
        RoomEvent event = new RoomEvent(
                roomId,
                nextSeq,
                RoomEventType.PIN_REMOVED,
                actorMemberId,
                sessionId,
                clientOpId,
                pinId,
                payloadJson,
                now
        );

        roomRepository.removePin(roomId, pinId, actorMemberId, now, nextSeq, event, nextSeq);

        // 4. DB write succeeded -> apply to in-memory state
        this.lastEventSeq = nextSeq;
        this.activePinsById.remove(pinId);
        this.activePinsByPlaceId.remove(pin.placeId());
        this.processedOps.put(clientOpId, new ProcessedOpResult(nextSeq, null, pinId, null));

        // 5. Broadcast
        String actorDisplayName = memberProfilePort.findDisplayName(actorMemberId);
        roomEventPublisher.publishEvent(event, null, actorDisplayName);

        return new PinRemoveResult(nextSeq, pinId, false);
    }

    public synchronized List<Pin> getActivePins() {
        var list = new ArrayList<>(activePinsById.values());
        list.sort(Comparator.comparingLong(Pin::order));
        return Collections.unmodifiableList(list);
    }

    public synchronized long getLastEventSeq() {
        return lastEventSeq;
    }

    public UUID getRoomId() {
        return roomId;
    }
}
