package com.yrootlab.onmaru.persistence.mapcollab;

import com.yrootlab.onmaru.mapcollab.domain.Invite;
import com.yrootlab.onmaru.mapcollab.domain.Member;
import com.yrootlab.onmaru.mapcollab.domain.MemberRole;
import com.yrootlab.onmaru.mapcollab.domain.Pin;
import com.yrootlab.onmaru.mapcollab.domain.Room;
import com.yrootlab.onmaru.mapcollab.domain.RoomEvent;
import com.yrootlab.onmaru.mapcollab.domain.UserRoomMembership;
import com.yrootlab.onmaru.mapcollab.port.CatalogPlaceInfo;
import com.yrootlab.onmaru.mapcollab.port.MemberProfilePort;
import com.yrootlab.onmaru.mapcollab.port.PlaceCatalogPort;
import com.yrootlab.onmaru.mapcollab.port.RoomRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Configuration(proxyBeanMethods = false)
public class MapCollabPersistenceConfiguration {

    @Bean
    @ConditionalOnBean(DataSource.class)
    @ConditionalOnMissingBean(RoomRepository.class)
    public RoomRepository jdbcRoomRepository(DataSource dataSource) {
        return new JdbcRoomRepository(dataSource);
    }

    @Bean
    @ConditionalOnMissingBean(RoomRepository.class)
    public RoomRepository inMemoryRoomRepository() {
        return new InMemoryRoomRepository();
    }

    @Bean
    @ConditionalOnBean(DataSource.class)
    @ConditionalOnMissingBean(PlaceCatalogPort.class)
    public PlaceCatalogPort jdbcPlaceCatalogAdapter(DataSource dataSource) {
        return new JdbcPlaceCatalogAdapter(dataSource);
    }

    @Bean
    @ConditionalOnMissingBean(PlaceCatalogPort.class)
    public PlaceCatalogPort inMemoryPlaceCatalogPort() {
        return publicId -> Optional.of(new CatalogPlaceInfo(publicId, "테스트 장소", 37.5, 127.0));
    }

    @Bean
    @ConditionalOnBean(DataSource.class)
    @ConditionalOnMissingBean(MemberProfilePort.class)
    public MemberProfilePort jdbcMemberProfileAdapter(DataSource dataSource) {
        return new JdbcMemberProfileAdapter(dataSource);
    }

    @Bean
    @ConditionalOnMissingBean(MemberProfilePort.class)
    public MemberProfilePort inMemoryMemberProfilePort() {
        return new MemberProfilePort() {
            @Override
            public String findDisplayName(UUID memberId) {
                return "사용자";
            }

            @Override
            public Map<UUID, String> findDisplayNames(Set<UUID> memberIds) {
                return memberIds.stream().collect(Collectors.toMap(id -> id, id -> "사용자"));
            }
        };
    }

    @Bean
    @ConditionalOnBean(DataSource.class)
    @ConditionalOnMissingBean(RoomSessionStore.class)
    public RoomSessionStore jdbcRoomSessionStore(DataSource dataSource) {
        return new JdbcRoomSessionStore(dataSource);
    }

    @Bean
    @ConditionalOnMissingBean(RoomSessionStore.class)
    public RoomSessionStore noOpRoomSessionStore() {
        return RoomSessionStore.noOp();
    }

    static class InMemoryRoomRepository implements RoomRepository {
        private final Map<UUID, Room> rooms = new ConcurrentHashMap<>();
        private final Map<UUID, List<Member>> members = new ConcurrentHashMap<>();
        private final Map<String, Invite> invitesByHash = new ConcurrentHashMap<>();
        private final Map<UUID, List<Invite>> invitesByRoom = new ConcurrentHashMap<>();
        private final Map<UUID, List<Pin>> pinsByRoom = new ConcurrentHashMap<>();
        private final Map<UUID, List<RoomEvent>> eventsByRoom = new ConcurrentHashMap<>();

        @Override
        public void saveRoomWithInitialMemberAndEvent(Room room, Member initialMember, RoomEvent createEvent) {
            rooms.put(room.id(), room);
            members.computeIfAbsent(room.id(), r -> Collections.synchronizedList(new ArrayList<>())).add(initialMember);
            eventsByRoom.computeIfAbsent(room.id(), r -> Collections.synchronizedList(new ArrayList<>())).add(createEvent);
        }

        @Override
        public Optional<Room> findRoomById(UUID roomId) {
            return Optional.ofNullable(rooms.get(roomId));
        }

        @Override
        public boolean isMember(UUID roomId, UUID memberId) {
            var list = members.get(roomId);
            return list != null && list.stream().anyMatch(m -> m.memberId().equals(memberId));
        }

        @Override
        public Optional<MemberRole> findMemberRole(UUID roomId, UUID memberId) {
            var list = members.get(roomId);
            if (list == null) return Optional.empty();
            return list.stream()
                    .filter(m -> m.memberId().equals(memberId))
                    .map(Member::role)
                    .findFirst();
        }

        @Override
        public List<Member> findMembersByRoomId(UUID roomId) {
            var list = members.get(roomId);
            return list != null ? new ArrayList<>(list) : List.of();
        }

        @Override
        public void saveInvite(Invite invite, RoomEvent inviteEvent) {
            invitesByHash.put(invite.tokenHash(), invite);
            invitesByRoom.computeIfAbsent(invite.roomId(), r -> Collections.synchronizedList(new ArrayList<>())).add(invite);
            eventsByRoom.computeIfAbsent(invite.roomId(), r -> Collections.synchronizedList(new ArrayList<>())).add(inviteEvent);
        }

        @Override
        public Optional<Invite> findInviteByTokenHash(String tokenHash) {
            return Optional.ofNullable(invitesByHash.get(tokenHash));
        }

        @Override
        public Optional<Invite> findInviteById(UUID roomId, UUID inviteId) {
            var list = invitesByRoom.get(roomId);
            if (list == null) return Optional.empty();
            return list.stream().filter(i -> i.id().equals(inviteId)).findFirst();
        }

        @Override
        public void revokeInvite(UUID roomId, UUID inviteId, Instant revokedAt, RoomEvent revokeEvent) {
            var list = invitesByRoom.get(roomId);
            if (list != null) {
                for (int i = 0; i < list.size(); i++) {
                    Invite inv = list.get(i);
                    if (inv.id().equals(inviteId)) {
                        Invite updated = new Invite(inv.id(), inv.roomId(), inv.tokenHash(), inv.createdBy(), inv.createdAt(), inv.expiresAt(), revokedAt);
                        list.set(i, updated);
                        invitesByHash.put(inv.tokenHash(), updated);
                        break;
                    }
                }
            }
            eventsByRoom.computeIfAbsent(roomId, r -> Collections.synchronizedList(new ArrayList<>())).add(revokeEvent);
        }

        @Override
        public long countActiveInvites(UUID roomId, Instant now) {
            var list = invitesByRoom.get(roomId);
            if (list == null) return 0;
            return list.stream().filter(i -> i.isValid(now)).count();
        }

        @Override
        public void addMember(Member member, RoomEvent joinEvent) {
            members.computeIfAbsent(member.roomId(), r -> Collections.synchronizedList(new ArrayList<>())).add(member);
            eventsByRoom.computeIfAbsent(member.roomId(), r -> Collections.synchronizedList(new ArrayList<>())).add(joinEvent);
        }

        @Override
        public long countMembers(UUID roomId) {
            var list = members.get(roomId);
            return list != null ? list.size() : 0;
        }

        @Override
        public List<UserRoomMembership> findActiveRoomsByMemberId(UUID memberId, Instant now) {
            List<UserRoomMembership> result = new ArrayList<>();
            for (var entry : members.entrySet()) {
                UUID roomId = entry.getKey();
                for (Member m : entry.getValue()) {
                    if (m.memberId().equals(memberId)) {
                        Room r = rooms.get(roomId);
                        if (r != null && r.isActive() && !r.isExpired(now)) {
                            result.add(new UserRoomMembership(roomId, r.status(), r.expiresAt(), m.role(), m.joinedAt()));
                        }
                    }
                }
            }
            return result;
        }

        @Override
        public List<Pin> findActivePins(UUID roomId) {
            var list = pinsByRoom.get(roomId);
            if (list == null) return List.of();
            return list.stream().filter(Pin::isActive).toList();
        }

        @Override
        public Optional<Pin> findActivePinByPlaceId(UUID roomId, String placeId) {
            var list = pinsByRoom.get(roomId);
            if (list == null) return Optional.empty();
            return list.stream().filter(p -> p.isActive() && p.placeId().equals(placeId)).findFirst();
        }

        @Override
        public Optional<Pin> findActivePinById(UUID roomId, UUID pinId) {
            var list = pinsByRoom.get(roomId);
            if (list == null) return Optional.empty();
            return list.stream().filter(p -> p.isActive() && p.pinId().equals(pinId)).findFirst();
        }

        @Override
        public void addPin(Pin pin, RoomEvent event, long newLastEventSeq) {
            pinsByRoom.computeIfAbsent(pin.roomId(), r -> Collections.synchronizedList(new ArrayList<>())).add(pin);
            eventsByRoom.computeIfAbsent(pin.roomId(), r -> Collections.synchronizedList(new ArrayList<>())).add(event);
            Room r = rooms.get(pin.roomId());
            if (r != null) {
                rooms.put(r.id(), new Room(r.id(), r.createdBy(), r.status(), r.createdAt(), r.expiresAt(), r.closedAt(), r.closedBy(), newLastEventSeq));
            }
        }

        @Override
        public void removePin(UUID roomId, UUID pinId, UUID removedBy, Instant removedAt, long removedSeq, RoomEvent event, long newLastEventSeq) {
            var list = pinsByRoom.get(roomId);
            if (list != null) {
                for (int i = 0; i < list.size(); i++) {
                    Pin p = list.get(i);
                    if (p.pinId().equals(pinId) && p.isActive()) {
                        list.set(i, new Pin(p.pinId(), p.roomId(), p.placeId(), p.placeName(), p.lat(), p.lng(), p.addedBy(), p.addedAt(), p.order(), removedBy, removedAt, removedSeq));
                        break;
                    }
                }
            }
            eventsByRoom.computeIfAbsent(roomId, r -> Collections.synchronizedList(new ArrayList<>())).add(event);
            Room r = rooms.get(roomId);
            if (r != null) {
                rooms.put(r.id(), new Room(r.id(), r.createdBy(), r.status(), r.createdAt(), r.expiresAt(), r.closedAt(), r.closedBy(), newLastEventSeq));
            }
        }

        @Override
        public Optional<RoomEvent> findEventByOpId(UUID roomId, UUID clientOpId) {
            var list = eventsByRoom.get(roomId);
            if (list == null) return Optional.empty();
            return list.stream().filter(e -> clientOpId.equals(e.clientOpId())).findFirst();
        }

        @Override
        public List<RoomEvent> findEventsAfter(UUID roomId, long lastSeq) {
            var list = eventsByRoom.get(roomId);
            if (list == null) return List.of();
            return list.stream().filter(e -> e.seq() > lastSeq).toList();
        }

        @Override
        public List<Room> findExpiredActiveRooms(Instant now, int limit) {
            return rooms.values().stream()
                    .filter(r -> r.isActive() && r.isExpired(now))
                    .limit(limit)
                    .toList();
        }

        @Override
        public void closeRoom(UUID roomId, Instant closedAt, UUID closedBy, RoomEvent closeEvent) {
            Room r = rooms.get(roomId);
            if (r != null) {
                rooms.put(r.id(), new Room(r.id(), r.createdBy(), com.yrootlab.onmaru.mapcollab.domain.RoomStatus.CLOSED, r.createdAt(), r.expiresAt(), closedAt, closedBy, closeEvent.seq()));
            }
            eventsByRoom.computeIfAbsent(roomId, re -> Collections.synchronizedList(new ArrayList<>())).add(closeEvent);
        }
    }
}
