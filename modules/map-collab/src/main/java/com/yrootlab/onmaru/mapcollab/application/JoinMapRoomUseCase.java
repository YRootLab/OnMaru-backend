package com.yrootlab.onmaru.mapcollab.application;

import com.yrootlab.onmaru.mapcollab.domain.Invite;
import com.yrootlab.onmaru.mapcollab.domain.MapCollabLimits;
import com.yrootlab.onmaru.mapcollab.domain.Member;
import com.yrootlab.onmaru.mapcollab.domain.MemberRole;
import com.yrootlab.onmaru.mapcollab.domain.Room;
import com.yrootlab.onmaru.mapcollab.domain.RoomEvent;
import com.yrootlab.onmaru.mapcollab.domain.RoomEventType;
import com.yrootlab.onmaru.mapcollab.domain.TokenUtils;
import com.yrootlab.onmaru.mapcollab.domain.exception.InviteNotFoundException;
import com.yrootlab.onmaru.mapcollab.domain.exception.RoomClosedException;
import com.yrootlab.onmaru.mapcollab.domain.exception.RoomExpiredException;
import com.yrootlab.onmaru.mapcollab.domain.exception.RoomFullException;
import com.yrootlab.onmaru.mapcollab.domain.exception.ValidationException;
import com.yrootlab.onmaru.mapcollab.port.MemberProfilePort;
import com.yrootlab.onmaru.mapcollab.port.RoomEventPublisher;
import com.yrootlab.onmaru.mapcollab.port.RoomRepository;
import com.yrootlab.onmaru.mapcollab.runtime.RoomRegistry;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public final class JoinMapRoomUseCase {

    private final RoomRepository roomRepository;
    private final MemberProfilePort memberProfilePort;
    private final RoomEventPublisher roomEventPublisher;
    private final RoomRegistry roomRegistry;

    public record JoinResult(
            Room room,
            MemberRole role,
            boolean alreadyMember) {
    }

    public JoinMapRoomUseCase(
            RoomRepository roomRepository,
            MemberProfilePort memberProfilePort,
            RoomEventPublisher roomEventPublisher,
            RoomRegistry roomRegistry) {
        this.roomRepository = Objects.requireNonNull(roomRepository, "roomRepository must not be null");
        this.memberProfilePort = Objects.requireNonNull(memberProfilePort, "memberProfilePort must not be null");
        this.roomEventPublisher = Objects.requireNonNull(roomEventPublisher, "roomEventPublisher must not be null");
        this.roomRegistry = Objects.requireNonNull(roomRegistry, "roomRegistry must not be null");
    }

    public JoinResult execute(String rawToken, UUID memberId, Instant now) {
        if (!TokenUtils.isValidTokenFormat(rawToken)) {
            throw new ValidationException("invalid invite token format", Map.of("field", "token"));
        }

        String tokenHash = TokenUtils.sha256Hex(rawToken);
        Invite invite = roomRepository.findInviteByTokenHash(tokenHash)
                .orElseThrow(InviteNotFoundException::new);

        if (!invite.isValid(now)) {
            throw new InviteNotFoundException();
        }

        Room room = roomRepository.findRoomById(invite.roomId())
                .orElseThrow(InviteNotFoundException::new);

        if (!room.isActive()) {
            throw new RoomClosedException();
        }
        if (room.isExpired(now)) {
            throw new RoomExpiredException();
        }

        // Idempotency: if already member, return 200 with alreadyMember = true
        Optional<MemberRole> existingRole = roomRepository.findMemberRole(room.id(), memberId);
        if (existingRole.isPresent()) {
            return new JoinResult(room, existingRole.get(), true);
        }

        // Capacity check: max 20 members
        long currentMembers = roomRepository.countMembers(room.id());
        if (currentMembers >= MapCollabLimits.MAX_MEMBERS_PER_ROOM) {
            throw new RoomFullException();
        }

        String displayName = memberProfilePort.findDisplayName(memberId);
        Member member = new Member(room.id(), memberId, displayName, MemberRole.MEMBER, now);

        var actor = roomRegistry.getOrCreate(room.id());
        synchronized (actor) {
            long nextSeq = actor.getLastEventSeq() + 1;
            RoomEvent joinEvent = new RoomEvent(
                    room.id(),
                    nextSeq,
                    RoomEventType.MEMBER_JOINED,
                    memberId,
                    null,
                    null,
                    null,
                    "{\"memberId\":\"" + memberId + "\"}",
                    now
            );
            roomRepository.addMember(member, joinEvent);
            roomEventPublisher.publishEvent(joinEvent, null, displayName);
        }

        return new JoinResult(room, MemberRole.MEMBER, false);
    }
}
