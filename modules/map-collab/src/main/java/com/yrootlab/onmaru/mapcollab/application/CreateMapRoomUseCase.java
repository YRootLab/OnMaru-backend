package com.yrootlab.onmaru.mapcollab.application;

import com.yrootlab.onmaru.mapcollab.domain.Invite;
import com.yrootlab.onmaru.mapcollab.domain.MapCollabLimits;
import com.yrootlab.onmaru.mapcollab.domain.Member;
import com.yrootlab.onmaru.mapcollab.domain.MemberRole;
import com.yrootlab.onmaru.mapcollab.domain.Room;
import com.yrootlab.onmaru.mapcollab.domain.RoomEvent;
import com.yrootlab.onmaru.mapcollab.domain.RoomEventType;
import com.yrootlab.onmaru.mapcollab.domain.RoomStatus;
import com.yrootlab.onmaru.mapcollab.domain.TokenUtils;
import com.yrootlab.onmaru.mapcollab.port.MemberProfilePort;
import com.yrootlab.onmaru.mapcollab.port.PlaceCatalogPort;
import com.yrootlab.onmaru.mapcollab.port.RoomEventPublisher;
import com.yrootlab.onmaru.mapcollab.port.RoomRepository;
import com.yrootlab.onmaru.mapcollab.runtime.RoomActor;
import com.yrootlab.onmaru.mapcollab.runtime.RoomRegistry;

import java.time.Instant;
import java.util.Collections;
import java.util.Objects;
import java.util.UUID;

public final class CreateMapRoomUseCase {

    private final RoomRepository roomRepository;
    private final MemberProfilePort memberProfilePort;
    private final PlaceCatalogPort placeCatalogPort;
    private final RoomEventPublisher roomEventPublisher;
    private final RoomRegistry roomRegistry;

    public record CreateRoomResult(
            Room room,
            MemberRole role,
            String token,
            String shareUrl,
            Instant expiresAt) {
    }

    public CreateMapRoomUseCase(
            RoomRepository roomRepository,
            MemberProfilePort memberProfilePort,
            PlaceCatalogPort placeCatalogPort,
            RoomEventPublisher roomEventPublisher,
            RoomRegistry roomRegistry) {
        this.roomRepository = Objects.requireNonNull(roomRepository, "roomRepository must not be null");
        this.memberProfilePort = Objects.requireNonNull(memberProfilePort, "memberProfilePort must not be null");
        this.placeCatalogPort = Objects.requireNonNull(placeCatalogPort, "placeCatalogPort must not be null");
        this.roomEventPublisher = Objects.requireNonNull(roomEventPublisher, "roomEventPublisher must not be null");
        this.roomRegistry = Objects.requireNonNull(roomRegistry, "roomRegistry must not be null");
    }

    public CreateRoomResult execute(UUID creatorMemberId, Instant now) {
        UUID roomId = UUID.randomUUID();
        Instant roomExpiresAt = now.plus(MapCollabLimits.ROOM_LIFETIME);

        String rawToken = TokenUtils.generateRawToken();
        String tokenHash = TokenUtils.sha256Hex(rawToken);
        Instant inviteExpiresAt = TokenUtils.calculateInviteExpiresAt(now, roomExpiresAt);

        String creatorDisplayName = memberProfilePort.findDisplayName(creatorMemberId);
        Member initialMember = new Member(roomId, creatorMemberId, creatorDisplayName, MemberRole.OWNER, now);

        UUID inviteId = UUID.randomUUID();
        Invite invite = new Invite(inviteId, roomId, tokenHash, creatorMemberId, now, inviteExpiresAt);

        Room room = new Room(roomId, creatorMemberId, RoomStatus.ACTIVE, now, roomExpiresAt, 1L);
        RoomEvent createEvent = new RoomEvent(
                roomId,
                1L,
                RoomEventType.ROOM_CREATED,
                creatorMemberId,
                null,
                null,
                null,
                "{}",
                now
        );

        roomRepository.saveRoomWithInitialMemberAndEvent(room, initialMember, createEvent);

        RoomEvent inviteEvent = new RoomEvent(
                roomId,
                2L,
                RoomEventType.INVITE_ISSUED,
                creatorMemberId,
                null,
                null,
                null,
                "{\"inviteId\":\"" + inviteId + "\"}",
                now
        );
        roomRepository.saveInvite(invite, inviteEvent);

        Room roomWithSeq = room.withLastEventSeq(2L);
        RoomActor actor = new RoomActor(
                roomId,
                2L,
                Collections.emptyList(),
                roomRepository,
                placeCatalogPort,
                memberProfilePort,
                roomEventPublisher
        );
        roomRegistry.register(roomId, actor);

        String shareUrl = "https://onmaru.site/map?invite=" + rawToken;
        return new CreateRoomResult(roomWithSeq, MemberRole.OWNER, rawToken, shareUrl, inviteExpiresAt);
    }
}
