package com.yrootlab.onmaru.web.mapcollab;

import com.yrootlab.onmaru.identity.lifecycle.MemberLifecycleService;
import com.yrootlab.onmaru.identity.lifecycle.MemberSummary;
import com.yrootlab.onmaru.mapcollab.application.CreateMapRoomUseCase;
import com.yrootlab.onmaru.mapcollab.application.GetMapRoomDetailUseCase;
import com.yrootlab.onmaru.mapcollab.application.GetMapRoomSnapshotUseCase;
import com.yrootlab.onmaru.mapcollab.application.IssueMapRoomInviteUseCase;
import com.yrootlab.onmaru.mapcollab.application.JoinMapRoomUseCase;
import com.yrootlab.onmaru.mapcollab.application.ListMyMapRoomsUseCase;
import com.yrootlab.onmaru.mapcollab.application.RevokeMapRoomInviteUseCase;
import com.yrootlab.onmaru.mapcollab.domain.Room;
import com.yrootlab.onmaru.mapcollab.domain.exception.ForbiddenException;
import com.yrootlab.onmaru.mapcollab.domain.exception.RoomNotFoundException;
import com.yrootlab.onmaru.mapcollab.domain.exception.UnauthorizedException;
import com.yrootlab.onmaru.mapcollab.domain.exception.ValidationException;
import com.yrootlab.onmaru.mapcollab.port.RoomRepository;
import com.yrootlab.onmaru.mapcollab.runtime.RoomActor;
import com.yrootlab.onmaru.mapcollab.runtime.RoomRegistry;
import com.yrootlab.onmaru.web.mapcollab.dto.MapCollabDtos;
import com.yrootlab.onmaru.web.mapcollab.dto.MapCollabDtos.AddMapRoomPinRequest;
import com.yrootlab.onmaru.web.mapcollab.dto.MapCollabDtos.CreateMapRoomResponse;
import com.yrootlab.onmaru.web.mapcollab.dto.MapCollabDtos.InviteWithTokenDto;
import com.yrootlab.onmaru.web.mapcollab.dto.MapCollabDtos.IssueMapRoomInviteResponse;
import com.yrootlab.onmaru.web.mapcollab.dto.MapCollabDtos.JoinMapRoomRequest;
import com.yrootlab.onmaru.web.mapcollab.dto.MapCollabDtos.JoinMapRoomResponse;
import com.yrootlab.onmaru.web.mapcollab.dto.MapCollabDtos.MapRoomDetailResponse;
import com.yrootlab.onmaru.web.mapcollab.dto.MapCollabDtos.MapRoomPinCommandResponse;
import com.yrootlab.onmaru.web.mapcollab.dto.MapCollabDtos.MapRoomPinRemoveResponse;
import com.yrootlab.onmaru.web.mapcollab.dto.MapCollabDtos.MapRoomSnapshotResponse;
import com.yrootlab.onmaru.web.mapcollab.dto.MapCollabDtos.MemberDto;
import com.yrootlab.onmaru.web.mapcollab.dto.MapCollabDtos.MyMapRoomsResponse;
import com.yrootlab.onmaru.web.mapcollab.dto.MapCollabDtos.MyRoomItemDto;
import com.yrootlab.onmaru.web.mapcollab.dto.MapCollabDtos.PinDto;
import com.yrootlab.onmaru.web.mapcollab.dto.MapCollabDtos.RoomRefDto;
import com.yrootlab.onmaru.web.mapcollab.dto.MapCollabDtos.RoomWithRoleDto;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

@RestController
@RequestMapping("/api/v1/map-rooms")
public class MapRoomController {

    private static final String SESSION_COOKIE = "__Host-onmaru-session";
    private static final Pattern PLACE_ID_PATTERN = Pattern.compile("^p-[a-z0-9]+(-[a-z0-9]+)*$");

    private final MemberLifecycleService memberLifecycleService;
    private final CreateMapRoomUseCase createMapRoomUseCase;
    private final IssueMapRoomInviteUseCase issueMapRoomInviteUseCase;
    private final RevokeMapRoomInviteUseCase revokeMapRoomInviteUseCase;
    private final JoinMapRoomUseCase joinMapRoomUseCase;
    private final GetMapRoomDetailUseCase getMapRoomDetailUseCase;
    private final GetMapRoomSnapshotUseCase getMapRoomSnapshotUseCase;
    private final ListMyMapRoomsUseCase listMyMapRoomsUseCase;
    private final RoomRepository roomRepository;
    private final RoomRegistry roomRegistry;
    private final Clock clock;

    public MapRoomController(
            MemberLifecycleService memberLifecycleService,
            CreateMapRoomUseCase createMapRoomUseCase,
            IssueMapRoomInviteUseCase issueMapRoomInviteUseCase,
            RevokeMapRoomInviteUseCase revokeMapRoomInviteUseCase,
            JoinMapRoomUseCase joinMapRoomUseCase,
            GetMapRoomDetailUseCase getMapRoomDetailUseCase,
            GetMapRoomSnapshotUseCase getMapRoomSnapshotUseCase,
            ListMyMapRoomsUseCase listMyMapRoomsUseCase,
            RoomRepository roomRepository,
            RoomRegistry roomRegistry,
            Clock clock) {
        this.memberLifecycleService = Objects.requireNonNull(memberLifecycleService);
        this.createMapRoomUseCase = Objects.requireNonNull(createMapRoomUseCase);
        this.issueMapRoomInviteUseCase = Objects.requireNonNull(issueMapRoomInviteUseCase);
        this.revokeMapRoomInviteUseCase = Objects.requireNonNull(revokeMapRoomInviteUseCase);
        this.joinMapRoomUseCase = Objects.requireNonNull(joinMapRoomUseCase);
        this.getMapRoomDetailUseCase = Objects.requireNonNull(getMapRoomDetailUseCase);
        this.getMapRoomSnapshotUseCase = Objects.requireNonNull(getMapRoomSnapshotUseCase);
        this.listMyMapRoomsUseCase = Objects.requireNonNull(listMyMapRoomsUseCase);
        this.roomRepository = Objects.requireNonNull(roomRepository);
        this.roomRegistry = Objects.requireNonNull(roomRegistry);
        this.clock = Objects.requireNonNull(clock);
    }

    @PostMapping
    public ResponseEntity<CreateMapRoomResponse> createMapRoom(
            @CookieValue(name = SESSION_COOKIE, required = false) String sessionToken) {
        MemberSummary member = requireMember(sessionToken);
        Instant now = clock.instant();

        var result = createMapRoomUseCase.execute(member.id(), now);
        var response = new CreateMapRoomResponse(
                MapCollabDtos.SCHEMA_VERSION,
                RoomWithRoleDto.from(result.room(), result.role()),
                new InviteWithTokenDto(result.token(), result.shareUrl(), result.expiresAt())
        );
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping("/mine")
    public ResponseEntity<MyMapRoomsResponse> listMyMapRooms(
            @CookieValue(name = SESSION_COOKIE, required = false) String sessionToken) {
        MemberSummary member = requireMember(sessionToken);
        Instant now = clock.instant();

        var rooms = listMyMapRoomsUseCase.execute(member.id(), now).stream()
                .map(MyRoomItemDto::from)
                .toList();

        return ResponseEntity.ok(new MyMapRoomsResponse(MapCollabDtos.SCHEMA_VERSION, rooms));
    }

    @PostMapping("/joins")
    public ResponseEntity<JoinMapRoomResponse> joinMapRoom(
            @CookieValue(name = SESSION_COOKIE, required = false) String sessionToken,
            @RequestBody JoinMapRoomRequest request) {
        MemberSummary member = requireMember(sessionToken);
        if (request == null || request.token() == null || !com.yrootlab.onmaru.mapcollab.domain.TokenUtils.isValidTokenFormat(request.token())) {
            throw new ValidationException("token format is invalid", Map.of("field", "token"));
        }
        Instant now = clock.instant();

        var result = joinMapRoomUseCase.execute(request.token(), member.id(), now);
        var response = new JoinMapRoomResponse(
                MapCollabDtos.SCHEMA_VERSION,
                RoomRefDto.from(result.room()),
                result.role(),
                result.alreadyMember()
        );
        return ResponseEntity.ok(response);
    }

    @GetMapping("/{roomId}")
    public ResponseEntity<MapRoomDetailResponse> getMapRoom(
            @PathVariable UUID roomId,
            @CookieValue(name = SESSION_COOKIE, required = false) String sessionToken) {
        MemberSummary member = requireMember(sessionToken);

        var result = getMapRoomDetailUseCase.execute(roomId, member.id());
        var members = result.members().stream()
                .map(m -> new MemberDto(m.memberId(), m.displayName(), m.role(), m.joinedAt(), m.online()))
                .toList();

        var response = new MapRoomDetailResponse(
                MapCollabDtos.SCHEMA_VERSION,
                RoomWithRoleDto.from(result.room(), result.myRole()),
                members
        );
        return ResponseEntity.ok(response);
    }

    @GetMapping("/{roomId}/snapshot")
    public ResponseEntity<MapRoomSnapshotResponse> getMapRoomSnapshot(
            @PathVariable UUID roomId,
            @CookieValue(name = SESSION_COOKIE, required = false) String sessionToken) {
        MemberSummary member = requireMember(sessionToken);
        Instant now = clock.instant();

        var result = getMapRoomSnapshotUseCase.execute(roomId, member.id(), now);
        var pins = result.pins().stream()
                .map(PinDto::from)
                .toList();

        return ResponseEntity.ok(new MapRoomSnapshotResponse(
                MapCollabDtos.SCHEMA_VERSION,
                result.lastSeq(),
                pins
        ));
    }

    @PostMapping("/{roomId}/invites")
    public ResponseEntity<IssueMapRoomInviteResponse> issueMapRoomInvite(
            @PathVariable UUID roomId,
            @CookieValue(name = SESSION_COOKIE, required = false) String sessionToken) {
        MemberSummary member = requireMember(sessionToken);
        Instant now = clock.instant();

        var result = issueMapRoomInviteUseCase.execute(roomId, member.id(), now);
        var response = new IssueMapRoomInviteResponse(
                MapCollabDtos.SCHEMA_VERSION,
                new InviteWithTokenDto(result.token(), result.shareUrl(), result.expiresAt())
        );
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @DeleteMapping("/{roomId}/invites/{inviteId}")
    public ResponseEntity<Void> revokeMapRoomInvite(
            @PathVariable UUID roomId,
            @PathVariable UUID inviteId,
            @CookieValue(name = SESSION_COOKIE, required = false) String sessionToken) {
        MemberSummary member = requireMember(sessionToken);
        Instant now = clock.instant();

        revokeMapRoomInviteUseCase.execute(roomId, inviteId, member.id(), now);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{roomId}/pins")
    public ResponseEntity<MapRoomPinCommandResponse> addMapRoomPin(
            @PathVariable UUID roomId,
            @CookieValue(name = SESSION_COOKIE, required = false) String sessionToken,
            @RequestBody AddMapRoomPinRequest request) {
        MemberSummary member = requireMember(sessionToken);
        Instant now = clock.instant();

        if (request == null || request.opId() == null) {
            throw new ValidationException("opId must not be null", Map.of("field", "opId"));
        }
        if (request.placeId() == null || !PLACE_ID_PATTERN.matcher(request.placeId()).matches()) {
            throw new ValidationException("placeId must match the public place id format", Map.of("field", "placeId"));
        }

        Room room = roomRepository.findRoomById(roomId)
                .orElseThrow(RoomNotFoundException::new);

        if (!roomRepository.isMember(roomId, member.id())) {
            throw new ForbiddenException();
        }

        RoomActor actor = roomRegistry.getOrCreate(roomId);
        var result = actor.addPin(request.opId(), request.placeId(), member.id(), null, now, room.expiresAt());

        var response = new MapRoomPinCommandResponse(
                MapCollabDtos.SCHEMA_VERSION,
                result.seq(),
                PinDto.from(result.pin())
        );
        HttpStatus status = result.isReplay() ? HttpStatus.OK : HttpStatus.CREATED;
        return ResponseEntity.status(status).body(response);
    }

    @DeleteMapping("/{roomId}/pins/{pinId}")
    public ResponseEntity<MapRoomPinRemoveResponse> removeMapRoomPin(
            @PathVariable UUID roomId,
            @PathVariable UUID pinId,
            @RequestParam(name = "opId") UUID opId,
            @CookieValue(name = SESSION_COOKIE, required = false) String sessionToken) {
        MemberSummary member = requireMember(sessionToken);
        Instant now = clock.instant();

        if (opId == null) {
            throw new ValidationException("opId must not be null", Map.of("field", "opId"));
        }

        Room room = roomRepository.findRoomById(roomId)
                .orElseThrow(RoomNotFoundException::new);

        if (!roomRepository.isMember(roomId, member.id())) {
            throw new ForbiddenException();
        }

        RoomActor actor = roomRegistry.getOrCreate(roomId);
        var result = actor.removePin(opId, pinId, member.id(), null, now, room.expiresAt());

        var response = new MapRoomPinRemoveResponse(
                MapCollabDtos.SCHEMA_VERSION,
                result.seq(),
                result.pinId()
        );
        return ResponseEntity.ok(response);
    }

    private MemberSummary requireMember(String sessionToken) {
        if (sessionToken == null || sessionToken.isBlank()) {
            throw new UnauthorizedException("login required");
        }
        return memberLifecycleService.currentMember(sessionToken)
                .orElseThrow(() -> new UnauthorizedException("login required"));
    }
}
