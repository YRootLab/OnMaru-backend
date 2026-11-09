package com.yrootlab.onmaru.mapcollab.application;

import com.yrootlab.onmaru.mapcollab.domain.Invite;
import com.yrootlab.onmaru.mapcollab.domain.MapCollabLimits;
import com.yrootlab.onmaru.mapcollab.domain.Room;
import com.yrootlab.onmaru.mapcollab.domain.RoomEvent;
import com.yrootlab.onmaru.mapcollab.domain.RoomEventType;
import com.yrootlab.onmaru.mapcollab.domain.TokenUtils;
import com.yrootlab.onmaru.mapcollab.domain.exception.ForbiddenException;
import com.yrootlab.onmaru.mapcollab.domain.exception.InviteLimitExceededException;
import com.yrootlab.onmaru.mapcollab.domain.exception.RoomClosedException;
import com.yrootlab.onmaru.mapcollab.domain.exception.RoomExpiredException;
import com.yrootlab.onmaru.mapcollab.domain.exception.RoomNotFoundException;
import com.yrootlab.onmaru.mapcollab.port.RoomRepository;
import com.yrootlab.onmaru.mapcollab.runtime.RoomRegistry;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public final class IssueMapRoomInviteUseCase {

    private final RoomRepository roomRepository;
    private final RoomRegistry roomRegistry;

    public record IssueInviteResult(
            String token,
            String shareUrl,
            Instant expiresAt) {
    }

    public IssueMapRoomInviteUseCase(RoomRepository roomRepository, RoomRegistry roomRegistry) {
        this.roomRepository = Objects.requireNonNull(roomRepository, "roomRepository must not be null");
        this.roomRegistry = Objects.requireNonNull(roomRegistry, "roomRegistry must not be null");
    }

    public IssueInviteResult execute(UUID roomId, UUID memberId, Instant now) {
        Room room = roomRepository.findRoomById(roomId)
                .orElseThrow(RoomNotFoundException::new);

        if (!room.isActive()) {
            throw new RoomClosedException();
        }
        if (room.isExpired(now)) {
            throw new RoomExpiredException();
        }

        if (!roomRepository.isMember(roomId, memberId)) {
            throw new ForbiddenException();
        }

        long activeInvites = roomRepository.countActiveInvites(roomId, now);
        if (activeInvites >= MapCollabLimits.MAX_ACTIVE_INVITES_PER_ROOM) {
            throw new InviteLimitExceededException();
        }

        String rawToken = TokenUtils.generateRawToken();
        String tokenHash = TokenUtils.sha256Hex(rawToken);
        Instant inviteExpiresAt = TokenUtils.calculateInviteExpiresAt(now, room.expiresAt());

        UUID inviteId = UUID.randomUUID();
        Invite invite = new Invite(inviteId, roomId, tokenHash, memberId, now, inviteExpiresAt);

        // Sequence generation synchronized per room via RoomActor or DB lock
        var actor = roomRegistry.getOrCreate(roomId);
        long nextSeq;
        synchronized (actor) {
            nextSeq = actor.getLastEventSeq() + 1;
            RoomEvent inviteEvent = new RoomEvent(
                    roomId,
                    nextSeq,
                    RoomEventType.INVITE_ISSUED,
                    memberId,
                    null,
                    null,
                    null,
                    "{\"inviteId\":\"" + inviteId + "\"}",
                    now
            );
            roomRepository.saveInvite(invite, inviteEvent);
        }

        String shareUrl = "https://onmaru.site/map?invite=" + rawToken;
        return new IssueInviteResult(rawToken, shareUrl, inviteExpiresAt);
    }
}
