package com.yrootlab.onmaru.web.mapcollab.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.yrootlab.onmaru.mapcollab.domain.MemberRole;
import com.yrootlab.onmaru.mapcollab.domain.Pin;
import com.yrootlab.onmaru.mapcollab.domain.Room;
import com.yrootlab.onmaru.mapcollab.domain.RoomStatus;
import com.yrootlab.onmaru.mapcollab.domain.UserRoomMembership;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class MapCollabDtos {

    private MapCollabDtos() {}

    public static final String SCHEMA_VERSION = "1.2";

    public record RoomRefDto(
            UUID id,
            RoomStatus status,
            Instant expiresAt) {
        public static RoomRefDto from(Room room) {
            return new RoomRefDto(room.id(), room.status(), room.expiresAt());
        }
    }

    public record RoomWithRoleDto(
            UUID id,
            RoomStatus status,
            Instant expiresAt,
            MemberRole role) {
        public static RoomWithRoleDto from(Room room, MemberRole role) {
            return new RoomWithRoleDto(room.id(), room.status(), room.expiresAt(), role);
        }
    }

    public record InviteWithTokenDto(
            String token,
            String shareUrl,
            Instant expiresAt) {
    }

    public record CreateMapRoomResponse(
            String schemaVersion,
            RoomWithRoleDto room,
            InviteWithTokenDto invite) {
    }

    public record IssueMapRoomInviteResponse(
            String schemaVersion,
            InviteWithTokenDto invite) {
    }

    public record JoinMapRoomRequest(
            String token) {
    }

    public record JoinMapRoomResponse(
            String schemaVersion,
            RoomRefDto room,
            MemberRole role,
            boolean alreadyMember) {
    }

    public record MemberDto(
            UUID memberId,
            String displayName,
            MemberRole role,
            Instant joinedAt,
            boolean online) {
    }

    public record MapRoomDetailResponse(
            String schemaVersion,
            RoomWithRoleDto room,
            List<MemberDto> members) {
    }

    public record PinDto(
            UUID pinId,
            String placeId,
            String placeName,
            double lat,
            double lng,
            UUID addedBy,
            Instant addedAt,
            long order) {
        public static PinDto from(Pin pin) {
            return new PinDto(
                    pin.pinId(),
                    pin.placeId(),
                    pin.placeName(),
                    pin.lat(),
                    pin.lng(),
                    pin.addedBy(),
                    pin.addedAt(),
                    pin.order()
            );
        }
    }

    public record MapRoomSnapshotResponse(
            String schemaVersion,
            long lastSeq,
            List<PinDto> pins) {
    }

    public record MyRoomItemDto(
            UUID id,
            RoomStatus status,
            Instant expiresAt,
            MemberRole role,
            Instant joinedAt) {
        public static MyRoomItemDto from(UserRoomMembership m) {
            return new MyRoomItemDto(m.id(), m.status(), m.expiresAt(), m.role(), m.joinedAt());
        }
    }

    public record MyMapRoomsResponse(
            String schemaVersion,
            List<MyRoomItemDto> rooms) {
    }

    public record AddMapRoomPinRequest(
            UUID opId,
            String placeId) {
    }

    public record MapRoomPinCommandResponse(
            String schemaVersion,
            long seq,
            PinDto pin) {
    }

    public record MapRoomPinRemoveResponse(
            String schemaVersion,
            long seq,
            UUID pinId) {
    }
}
