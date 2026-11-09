package com.yrootlab.onmaru.mapcollab.runtime;

import com.yrootlab.onmaru.mapcollab.domain.Invite;
import com.yrootlab.onmaru.mapcollab.domain.Member;
import com.yrootlab.onmaru.mapcollab.domain.MemberRole;
import com.yrootlab.onmaru.mapcollab.domain.Pin;
import com.yrootlab.onmaru.mapcollab.domain.Room;
import com.yrootlab.onmaru.mapcollab.domain.RoomEvent;
import com.yrootlab.onmaru.mapcollab.domain.UserRoomMembership;
import com.yrootlab.onmaru.mapcollab.domain.exception.OpConflictException;
import com.yrootlab.onmaru.mapcollab.domain.exception.PinAlreadyExistsException;
import com.yrootlab.onmaru.mapcollab.domain.exception.PinLimitExceededException;
import com.yrootlab.onmaru.mapcollab.domain.exception.PinNotFoundException;
import com.yrootlab.onmaru.mapcollab.domain.exception.PlaceNotFoundException;
import com.yrootlab.onmaru.mapcollab.port.CatalogPlaceInfo;
import com.yrootlab.onmaru.mapcollab.port.MemberProfilePort;
import com.yrootlab.onmaru.mapcollab.port.PlaceCatalogPort;
import com.yrootlab.onmaru.mapcollab.port.RoomEventPublisher;
import com.yrootlab.onmaru.mapcollab.port.RoomRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RoomActorTests {

    private UUID roomId;
    private UUID actorMemberId;
    private Instant now;
    private Instant roomExpiresAt;
    private MockRoomRepository roomRepository;
    private MockPlaceCatalogPort placeCatalogPort;
    private MockMemberProfilePort memberProfilePort;
    private MockRoomEventPublisher roomEventPublisher;
    private RoomActor actor;

    @BeforeEach
    void setUp() {
        roomId = UUID.randomUUID();
        actorMemberId = UUID.randomUUID();
        now = Instant.parse("2026-11-10T00:00:00Z");
        roomExpiresAt = now.plus(72, ChronoUnit.HOURS);

        roomRepository = new MockRoomRepository();
        placeCatalogPort = new MockPlaceCatalogPort();
        memberProfilePort = new MockMemberProfilePort();
        roomEventPublisher = new MockRoomEventPublisher();

        placeCatalogPort.registerPlace(new CatalogPlaceInfo("p-okcheon-hanok", "옥천성당", 36.3, 127.7));
        placeCatalogPort.registerPlace(new CatalogPlaceInfo("p-gyeongju-bulguksa", "불국사", 35.79, 129.33));

        actor = new RoomActor(
                roomId,
                0L,
                Collections.emptyList(),
                roomRepository,
                placeCatalogPort,
                memberProfilePort,
                roomEventPublisher
        );
    }

    @Test
    void addPinSuccessIncrementsSeqAndUpdatesMemory() {
        UUID opId = UUID.randomUUID();
        var result = actor.addPin(opId, "p-okcheon-hanok", actorMemberId, null, now, roomExpiresAt);

        assertThat(result.seq()).isEqualTo(1L);
        assertThat(result.isReplay()).isFalse();
        assertThat(result.pin().placeId()).isEqualTo("p-okcheon-hanok");
        assertThat(result.pin().placeName()).isEqualTo("옥천성당");
        assertThat(result.pin().lat()).isEqualTo(36.3);
        assertThat(result.pin().lng()).isEqualTo(127.7);
        assertThat(result.pin().order()).isEqualTo(1L);

        assertThat(actor.getLastEventSeq()).isEqualTo(1L);
        assertThat(actor.getActivePins()).hasSize(1);
        assertThat(actor.getActivePins().get(0).pinId()).isEqualTo(result.pin().pinId());

        assertThat(roomRepository.addedPins).hasSize(1);
        assertThat(roomEventPublisher.publishedEvents).hasSize(1);
    }

    @Test
    void addPinReplaySameOpReturnsCachedResult() {
        UUID opId = UUID.randomUUID();
        var first = actor.addPin(opId, "p-okcheon-hanok", actorMemberId, null, now, roomExpiresAt);
        var second = actor.addPin(opId, "p-okcheon-hanok", actorMemberId, null, now, roomExpiresAt);

        assertThat(second.isReplay()).isTrue();
        assertThat(second.seq()).isEqualTo(first.seq());
        assertThat(second.pin().pinId()).isEqualTo(first.pin().pinId());

        // DB write was NOT called a second time
        assertThat(roomRepository.addedPins).hasSize(1);
        assertThat(roomEventPublisher.publishedEvents).hasSize(1);
    }

    @Test
    void addPinReplaySameOpWithDifferentPlaceThrowsOpConflict() {
        UUID opId = UUID.randomUUID();
        actor.addPin(opId, "p-okcheon-hanok", actorMemberId, null, now, roomExpiresAt);

        assertThatThrownBy(() -> actor.addPin(opId, "p-gyeongju-bulguksa", actorMemberId, null, now, roomExpiresAt))
                .isInstanceOf(OpConflictException.class);
    }

    @Test
    void addPinWithDuplicatePlaceInSameRoomThrowsPinAlreadyExists() {
        UUID opId1 = UUID.randomUUID();
        actor.addPin(opId1, "p-okcheon-hanok", actorMemberId, null, now, roomExpiresAt);

        UUID opId2 = UUID.randomUUID();
        assertThatThrownBy(() -> actor.addPin(opId2, "p-okcheon-hanok", actorMemberId, null, now, roomExpiresAt))
                .isInstanceOf(PinAlreadyExistsException.class)
                .satisfies(e -> {
                    PinAlreadyExistsException pae = (PinAlreadyExistsException) e;
                    assertThat(pae.getExistingPin().placeId()).isEqualTo("p-okcheon-hanok");
                    assertThat(pae.getDetails()).containsKey("existingPin");
                });
    }

    @Test
    void addPinWithUnknownPlaceThrowsPlaceNotFound() {
        UUID opId = UUID.randomUUID();
        assertThatThrownBy(() -> actor.addPin(opId, "p-unknown", actorMemberId, null, now, roomExpiresAt))
                .isInstanceOf(PlaceNotFoundException.class);
    }

    @Test
    void addPinDbFailureLeavesMemoryStateUnchanged() {
        roomRepository.failNextWrite = true;
        UUID opId = UUID.randomUUID();

        assertThatThrownBy(() -> actor.addPin(opId, "p-okcheon-hanok", actorMemberId, null, now, roomExpiresAt))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("Simulated DB failure");

        // In-memory state remains untouched
        assertThat(actor.getLastEventSeq()).isZero();
        assertThat(actor.getActivePins()).isEmpty();

        // Now retry after DB is healthy
        roomRepository.failNextWrite = false;
        var result = actor.addPin(opId, "p-okcheon-hanok", actorMemberId, null, now, roomExpiresAt);
        assertThat(result.seq()).isEqualTo(1L);
        assertThat(actor.getActivePins()).hasSize(1);
    }

    @Test
    void removePinSuccessRemovesFromActiveListAndAdvancesSeq() {
        UUID addOp = UUID.randomUUID();
        var addResult = actor.addPin(addOp, "p-okcheon-hanok", actorMemberId, null, now, roomExpiresAt);

        UUID removeOp = UUID.randomUUID();
        var removeResult = actor.removePin(removeOp, addResult.pin().pinId(), actorMemberId, null, now, roomExpiresAt);

        assertThat(removeResult.seq()).isEqualTo(2L);
        assertThat(removeResult.pinId()).isEqualTo(addResult.pin().pinId());
        assertThat(removeResult.isReplay()).isFalse();

        assertThat(actor.getLastEventSeq()).isEqualTo(2L);
        assertThat(actor.getActivePins()).isEmpty();

        // After removal, the same place can be added again
        UUID addOp2 = UUID.randomUUID();
        var addResult2 = actor.addPin(addOp2, "p-okcheon-hanok", actorMemberId, null, now, roomExpiresAt);
        assertThat(addResult2.seq()).isEqualTo(3L);
        assertThat(actor.getActivePins()).hasSize(1);
    }

    @Test
    void removePinNonExistentThrowsPinNotFound() {
        UUID removeOp = UUID.randomUUID();
        assertThatThrownBy(() -> actor.removePin(removeOp, UUID.randomUUID(), actorMemberId, null, now, roomExpiresAt))
                .isInstanceOf(PinNotFoundException.class);
    }

    @Test
    void removePinReplaySameOpReturnsCachedResult() {
        UUID addOp = UUID.randomUUID();
        var addResult = actor.addPin(addOp, "p-okcheon-hanok", actorMemberId, null, now, roomExpiresAt);

        UUID removeOp = UUID.randomUUID();
        var remove1 = actor.removePin(removeOp, addResult.pin().pinId(), actorMemberId, null, now, roomExpiresAt);
        var remove2 = actor.removePin(removeOp, addResult.pin().pinId(), actorMemberId, null, now, roomExpiresAt);

        assertThat(remove2.isReplay()).isTrue();
        assertThat(remove2.seq()).isEqualTo(remove1.seq());
        assertThat(roomRepository.removedPins).hasSize(1);
    }

    @Test
    void removePinDbFailureLeavesMemoryStateUnchanged() {
        UUID addOp = UUID.randomUUID();
        var addResult = actor.addPin(addOp, "p-okcheon-hanok", actorMemberId, null, now, roomExpiresAt);

        roomRepository.failNextWrite = true;
        UUID removeOp = UUID.randomUUID();
        assertThatThrownBy(() -> actor.removePin(removeOp, addResult.pin().pinId(), actorMemberId, null, now, roomExpiresAt))
                .isInstanceOf(RuntimeException.class);

        // Pin should still be active in memory!
        assertThat(actor.getActivePins()).hasSize(1);
        assertThat(actor.getLastEventSeq()).isEqualTo(1L);
    }

    // Mock classes for testing
    static class MockPlaceCatalogPort implements PlaceCatalogPort {
        private final Map<String, CatalogPlaceInfo> places = new HashMap<>();

        void registerPlace(CatalogPlaceInfo place) { places.put(place.publicId(), place); }

        @Override
        public Optional<CatalogPlaceInfo> findPlaceByPublicId(String publicId) {
            return Optional.ofNullable(places.get(publicId));
        }
    }

    static class MockMemberProfilePort implements MemberProfilePort {
        @Override
        public Map<UUID, String> findDisplayNames(Set<UUID> memberIds) {
            Map<UUID, String> map = new HashMap<>();
            for (UUID id : memberIds) map.put(id, "테스트유저");
            return map;
        }
    }

    static class MockRoomEventPublisher implements RoomEventPublisher {
        final List<RoomEvent> publishedEvents = new ArrayList<>();

        @Override
        public void publishEvent(RoomEvent event, Pin pin, String actorDisplayName) {
            publishedEvents.add(event);
        }

        @Override public void publishPresence(UUID roomId, UUID memberId, boolean online) {}
        @Override public void closeRoomSessions(UUID roomId, int closeCode, String reason) {}
    }

    static class MockRoomRepository implements RoomRepository {
        boolean failNextWrite = false;
        final List<Pin> addedPins = new ArrayList<>();
        final List<UUID> removedPins = new ArrayList<>();
        final Map<UUID, RoomEvent> eventsByOpId = new HashMap<>();

        @Override public void saveRoomWithInitialMemberAndEvent(Room room, Member initialMember, RoomEvent createEvent) {}
        @Override public Optional<Room> findRoomById(UUID roomId) { return Optional.empty(); }
        @Override public boolean isMember(UUID roomId, UUID memberId) { return true; }
        @Override public Optional<MemberRole> findMemberRole(UUID roomId, UUID memberId) { return Optional.of(MemberRole.MEMBER); }
        @Override public List<Member> findMembersByRoomId(UUID roomId) { return List.of(); }
        @Override public void saveInvite(Invite invite, RoomEvent inviteEvent) {}
        @Override public Optional<Invite> findInviteByTokenHash(String tokenHash) { return Optional.empty(); }
        @Override public Optional<Invite> findInviteById(UUID roomId, UUID inviteId) { return Optional.empty(); }
        @Override public void revokeInvite(UUID roomId, UUID inviteId, Instant revokedAt, RoomEvent revokeEvent) {}
        @Override public long countActiveInvites(UUID roomId, Instant now) { return 0; }
        @Override public void addMember(Member member, RoomEvent joinEvent) {}
        @Override public long countMembers(UUID roomId) { return 1; }
        @Override public List<UserRoomMembership> findActiveRoomsByMemberId(UUID memberId, Instant now) { return List.of(); }
        @Override public List<Pin> findActivePins(UUID roomId) { return List.of(); }
        @Override public Optional<Pin> findActivePinByPlaceId(UUID roomId, String placeId) { return Optional.empty(); }
        @Override public Optional<Pin> findActivePinById(UUID roomId, UUID pinId) { return Optional.empty(); }

        @Override
        public void addPin(Pin pin, RoomEvent event, long newLastEventSeq) {
            if (failNextWrite) throw new RuntimeException("Simulated DB failure");
            addedPins.add(pin);
            if (event.clientOpId() != null) eventsByOpId.put(event.clientOpId(), event);
        }

        @Override
        public void removePin(UUID roomId, UUID pinId, UUID removedBy, Instant removedAt, long removedSeq, RoomEvent event, long newLastEventSeq) {
            if (failNextWrite) throw new RuntimeException("Simulated DB failure");
            removedPins.add(pinId);
            if (event.clientOpId() != null) eventsByOpId.put(event.clientOpId(), event);
        }

        @Override
        public Optional<RoomEvent> findEventByOpId(UUID roomId, UUID clientOpId) {
            return Optional.ofNullable(eventsByOpId.get(clientOpId));
        }

        @Override public List<RoomEvent> findEventsAfter(UUID roomId, long lastSeq) { return List.of(); }
        @Override public List<Room> findExpiredActiveRooms(Instant now, int limit) { return List.of(); }
        @Override public void closeRoom(UUID roomId, Instant closedAt, UUID closedBy, RoomEvent closeEvent) {}
    }
}
