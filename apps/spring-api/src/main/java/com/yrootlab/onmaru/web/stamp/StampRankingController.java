package com.yrootlab.onmaru.web.stamp;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.yrootlab.onmaru.identity.lifecycle.MemberLifecycleService;
import com.yrootlab.onmaru.security.web.PrivateResponse;
import com.yrootlab.onmaru.stamp.ranking.StampLeaderboard;
import com.yrootlab.onmaru.stamp.ranking.StampRankingEntry;
import com.yrootlab.onmaru.stamp.ranking.StampRankingInputInvalidException;
import com.yrootlab.onmaru.stamp.ranking.StampRankingNicknameType;
import com.yrootlab.onmaru.stamp.ranking.StampRankingService;
import com.yrootlab.onmaru.stamp.ranking.StampRankingStatus;
import com.yrootlab.onmaru.web.common.error.ApiErrorResponse;
import com.yrootlab.onmaru.web.common.error.RequestIdFilter;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.yrootlab.onmaru.web.stamp.StampApiContract.SCHEMA_VERSION;

@Tag(name = "수결 랭킹", description = "익명 공개 랭킹과 개인 참여 설정 API")
@RestController
public final class StampRankingController {
    private static final String SESSION_COOKIE = "__Host-onmaru-session";
    private final StampRankingService rankings;
    private final MemberLifecycleService members;

    StampRankingController(StampRankingService rankings, MemberLifecycleService members) {
        this.rankings = rankings;
        this.members = members;
    }

    @Operation(summary = "익명 수결 랭킹 조회")
    @GetMapping("/api/v1/stamps/leaderboard")
    ResponseEntity<StampLeaderboardResponse> leaderboard(@RequestParam(defaultValue = "20") int limit) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(StampLeaderboardResponse.from(rankings.leaderboard(limit)));
    }

    @Operation(summary = "내 수결 랭킹 참여 상태 조회")
    @PrivateResponse
    @GetMapping("/api/v1/me/stamp-ranking")
    ResponseEntity<?> status(
            @CookieValue(name = SESSION_COOKIE, required = false) String session,
            HttpServletRequest request) {
        return members.currentMember(session)
                .<ResponseEntity<?>>map(member -> personalResponse(rankings.status(member.id())))
                .orElseGet(() -> authRequired(request));
    }

    @Operation(summary = "내 수결 랭킹 참여 설정 변경")
    @PrivateResponse
    @PutMapping("/api/v1/me/stamp-ranking")
    ResponseEntity<?> update(
            @RequestBody(required = false) StampRankingUpdateRequest body,
            @CookieValue(name = SESSION_COOKIE, required = false) String session,
            HttpServletRequest request) {
        return members.currentMember(session)
                .<ResponseEntity<?>>map(member -> personalResponse(rankings.update(member.id(), participating(body))))
                .orElseGet(() -> authRequired(request));
    }

    private boolean participating(StampRankingUpdateRequest body) {
        if (body == null || !body.unknownFields().isEmpty()) {
            throw new StampRankingInputInvalidException("body");
        }
        if (!(body.participating() instanceof Boolean participating)) {
            throw new StampRankingInputInvalidException("participating");
        }
        return participating;
    }

    private ResponseEntity<StampRankingStatusResponse> personalResponse(StampRankingStatus status) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(StampRankingStatusResponse.from(status));
    }

    private ResponseEntity<ApiErrorResponse> authRequired(HttpServletRequest request) {
        var value = request.getAttribute(RequestIdFilter.ATTRIBUTE);
        var requestId = value instanceof String existing && !existing.isBlank()
                ? existing : UUID.randomUUID().toString();
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).cacheControl(CacheControl.noStore())
                .body(new ApiErrorResponse(SCHEMA_VERSION, "AUTH_REQUIRED", "Authentication is required.",
                        requestId, Map.of()));
    }

    record StampRankingUpdateRequest(Object participating, Map<String, Object> unknownFields) {
        @JsonCreator
        StampRankingUpdateRequest(@JsonProperty("participating") Object participating) {
            this(participating, new LinkedHashMap<>());
        }

        @JsonAnySetter
        void unknown(String name, Object value) {
            unknownFields.put(name, value);
        }
    }

    record StampLeaderboardResponse(String schemaVersion, Instant generatedAt, List<RankingEntryResponse> entries) {
        static StampLeaderboardResponse from(StampLeaderboard value) {
            return new StampLeaderboardResponse(SCHEMA_VERSION, value.generatedAt(),
                    value.entries().stream().map(RankingEntryResponse::from).toList());
        }
    }

    record RankingEntryResponse(int rank, UUID publicId, String nickname, StampRankingNicknameType nicknameType,
                                int stampCount, int visitedRegionCount, int completionRate) {
        static RankingEntryResponse from(StampRankingEntry value) {
            return new RankingEntryResponse(value.rank(), value.publicId(), value.publicNickname(), value.nicknameType(),
                    value.stampCount(), value.visitedRegionCount(), value.completionRate());
        }
    }

    record StampRankingStatusResponse(String schemaVersion, boolean participating, String publicNickname,
                                      StampRankingNicknameType nicknameType, Integer rank, int participantCount,
                                      int stampCount, int visitedRegionCount, int completionRate) {
        static StampRankingStatusResponse from(StampRankingStatus value) {
            return new StampRankingStatusResponse(SCHEMA_VERSION, value.participating(), value.publicNickname(),
                    value.nicknameType(), value.rank(), value.participantCount(), value.stampCount(),
                    value.visitedRegionCount(), value.completionRate());
        }
    }
}
