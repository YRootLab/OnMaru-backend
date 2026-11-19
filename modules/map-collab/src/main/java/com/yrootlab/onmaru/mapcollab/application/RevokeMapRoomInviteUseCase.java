package com.yrootlab.onmaru.mapcollab.application;

import com.yrootlab.onmaru.mapcollab.domain.Invite;
import com.yrootlab.onmaru.mapcollab.domain.MemberRole;
import com.yrootlab.onmaru.mapcollab.domain.Room;
import com.yrootlab.onmaru.mapcollab.domain.RoomEvent;
import com.yrootlab.onmaru.mapcollab.domain.RoomEventType;
import com.yrootlab.onmaru.mapcollab.domain.exception.ForbiddenException;
import com.yrootlab.onmaru.mapcollab.domain.exception.InviteNotFoundException;
import com.yrootlab.onmaru.mapcollab.domain.exception.RoomNotFoundException;
import com.yrootlab.onmaru.mapcollab.port.RoomRepository;
import com.yrootlab.onmaru.mapcollab.runtime.RoomRegistry;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public final class RevokeMapRoomInviteUseCase {

    private final RoomRepository roomRepository;
    private final RoomRegistry roomRegistry;

    public RevokeMapRoomInviteUseCase(RoomRepository roomRepository, RoomRegistry roomRegistry) {
        this.roomRepository = Objects.requireNonNull(roomRepository, "roomRepository must not be null");
        this.roomRegistry = Objects.requireNonNull(roomRegistry, "roomRegistry must not be null");
    }

    public void execute(UUID roomId, UUID inviteId, UUID memberId, Instant now) {
        Room room = roomRepository.findRoomById(roomId)
                .orElseThrow(RoomNotFoundException::new);

        MemberRole role = roomRepository.findMemberRole(roomId, memberId)
                .orElseThrow(ForbiddenException::new);

        Invite invite = roomRepository.findInviteById(roomId, inviteId)
                .orElseThrow(InviteNotFoundException::new);

        // Idempotent revoke: if already revoked, return quietly
        if (invite.isRevoked()) {
            return;
        }

        // Permission: creator of invite or room OWNER
        if (!invite.createdBy().equals(memberId) && role != MemberRole.OWNER) {
            throw new ForbiddenException();
        }

        var actor = roomRegistry.getOrCreate(roomId);
        synchronized (actor) {
            long nextSeq = actor.getLastEventSeq() + 1;
            RoomEvent revokeEvent = new RoomEvent(
                    roomId,
                    nextSeq,
                    RoomEventType.INVITE_REVOKED,
                    memberId,
                    null,
                    null,
                    null,
                    "{\"inviteId\":\"" + inviteId + "\"}",
                    now
            );
            roomRepository.revokeInvite(roomId, inviteId, now, revokeEvent);
        }
    }
}
