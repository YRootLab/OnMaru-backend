package com.yrootlab.onmaru.mapcollab.port;

import com.yrootlab.onmaru.mapcollab.domain.Invite;
import com.yrootlab.onmaru.mapcollab.domain.Member;
import com.yrootlab.onmaru.mapcollab.domain.MemberRole;
import com.yrootlab.onmaru.mapcollab.domain.Pin;
import com.yrootlab.onmaru.mapcollab.domain.Room;
import com.yrootlab.onmaru.mapcollab.domain.RoomEvent;
import com.yrootlab.onmaru.mapcollab.domain.UserRoomMembership;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RoomRepository {

    void saveRoomWithInitialMemberAndEvent(Room room, Member initialMember, RoomEvent createEvent);

    Optional<Room> findRoomById(UUID roomId);

    boolean isMember(UUID roomId, UUID memberId);

    Optional<MemberRole> findMemberRole(UUID roomId, UUID memberId);

    List<Member> findMembersByRoomId(UUID roomId);

    void saveInvite(Invite invite, RoomEvent inviteEvent);

    Optional<Invite> findInviteByTokenHash(String tokenHash);

    Optional<Invite> findInviteById(UUID roomId, UUID inviteId);

    void revokeInvite(UUID roomId, UUID inviteId, Instant revokedAt, RoomEvent revokeEvent);

    long countActiveInvites(UUID roomId, Instant now);

    void addMember(Member member, RoomEvent joinEvent);

    long countMembers(UUID roomId);

    List<UserRoomMembership> findActiveRoomsByMemberId(UUID memberId, Instant now);

    List<Pin> findActivePins(UUID roomId);

    Optional<Pin> findActivePinByPlaceId(UUID roomId, String placeId);

    Optional<Pin> findActivePinById(UUID roomId, UUID pinId);

    void addPin(Pin pin, RoomEvent event, long newLastEventSeq);

    void removePin(UUID roomId, UUID pinId, UUID removedBy, Instant removedAt, long removedSeq, RoomEvent event, long newLastEventSeq);

    Optional<RoomEvent> findEventByOpId(UUID roomId, UUID clientOpId);

    List<RoomEvent> findEventsAfter(UUID roomId, long lastSeq);

    List<Room> findExpiredActiveRooms(Instant now, int limit);

    void closeRoom(UUID roomId, Instant closedAt, UUID closedBy, RoomEvent closeEvent);
}
