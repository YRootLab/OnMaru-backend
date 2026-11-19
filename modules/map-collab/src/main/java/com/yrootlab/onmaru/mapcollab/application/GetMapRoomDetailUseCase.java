package com.yrootlab.onmaru.mapcollab.application;

import com.yrootlab.onmaru.mapcollab.domain.Member;
import com.yrootlab.onmaru.mapcollab.domain.MemberRole;
import com.yrootlab.onmaru.mapcollab.domain.Room;
import com.yrootlab.onmaru.mapcollab.domain.exception.ForbiddenException;
import com.yrootlab.onmaru.mapcollab.domain.exception.RoomNotFoundException;
import com.yrootlab.onmaru.mapcollab.port.MemberProfilePort;
import com.yrootlab.onmaru.mapcollab.port.RoomRepository;
import com.yrootlab.onmaru.mapcollab.port.RoomSessionTracker;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

public final class GetMapRoomDetailUseCase {

    private final RoomRepository roomRepository;
    private final MemberProfilePort memberProfilePort;
    private final RoomSessionTracker roomSessionTracker;

    public record MemberDetail(
            UUID memberId,
            String displayName,
            MemberRole role,
            Instant joinedAt,
            boolean online) {
    }

    public record RoomDetailResult(
            Room room,
            MemberRole myRole,
            List<MemberDetail> members) {
    }

    public GetMapRoomDetailUseCase(
            RoomRepository roomRepository,
            MemberProfilePort memberProfilePort,
            RoomSessionTracker roomSessionTracker) {
        this.roomRepository = Objects.requireNonNull(roomRepository, "roomRepository must not be null");
        this.memberProfilePort = Objects.requireNonNull(memberProfilePort, "memberProfilePort must not be null");
        this.roomSessionTracker = Objects.requireNonNull(roomSessionTracker, "roomSessionTracker must not be null");
    }

    public RoomDetailResult execute(UUID roomId, UUID requestingMemberId) {
        Room room = roomRepository.findRoomById(roomId)
                .orElseThrow(RoomNotFoundException::new);

        MemberRole myRole = roomRepository.findMemberRole(roomId, requestingMemberId)
                .orElseThrow(ForbiddenException::new);

        List<Member> members = roomRepository.findMembersByRoomId(roomId);
        Set<UUID> memberIds = members.stream().map(Member::memberId).collect(Collectors.toSet());
        Map<UUID, String> displayNames = memberProfilePort.findDisplayNames(memberIds);

        List<MemberDetail> memberDetails = new ArrayList<>();
        for (Member m : members) {
            String name = displayNames.getOrDefault(m.memberId(), m.displayName());
            if (name.isBlank()) {
                name = "사용자";
            }
            boolean online = roomSessionTracker.isMemberOnline(roomId, m.memberId());
            memberDetails.add(new MemberDetail(m.memberId(), name, m.role(), m.joinedAt(), online));
        }

        return new RoomDetailResult(room, myRole, memberDetails);
    }
}
