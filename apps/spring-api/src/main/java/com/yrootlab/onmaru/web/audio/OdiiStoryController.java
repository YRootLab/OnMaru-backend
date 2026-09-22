package com.yrootlab.onmaru.web.audio;

import com.yrootlab.onmaru.audio.query.OdiiCursorExpiredException;
import com.yrootlab.onmaru.audio.query.OdiiCursorInvalidException;
import com.yrootlab.onmaru.audio.query.OdiiStoryInvalidRequestException;
import com.yrootlab.onmaru.audio.query.OdiiStoryNotFoundException;
import com.yrootlab.onmaru.audio.query.OdiiStoryPopularityPort;
import com.yrootlab.onmaru.audio.query.OdiiStoryQuery;
import com.yrootlab.onmaru.audio.query.OdiiStoryQueryService;
import com.yrootlab.onmaru.audio.query.OdiiStoryUnavailableException;
import com.yrootlab.onmaru.identity.lifecycle.MemberLifecycleService;
import com.yrootlab.onmaru.web.common.error.ApiErrorCode;
import com.yrootlab.onmaru.web.common.error.ApiErrorResponse;
import com.yrootlab.onmaru.web.common.error.RequestIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import io.swagger.v3.oas.annotations.Hidden;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Tag(name = "02. 오디 오디오 도슨트 (Odii Audio)", description = "한국관광공사 오디(Odii) 기반 장소별 오디오 도슨트 해설 및 스크립트 API")
@RestController
public final class OdiiStoryController {

    private static final String SESSION_COOKIE = "__Host-onmaru-session";

    private final OdiiStoryQueryService queryService;
    private final OdiiStoryPopularityPort popularityPort;
    private final Clock clock;
    private final MemberLifecycleService memberLifecycleService;

    OdiiStoryController(
            OdiiStoryQueryService queryService,
            OdiiStoryPopularityPort popularityPort,
            Clock clock,
            MemberLifecycleService memberLifecycleService) {
        this.queryService = queryService;
        this.popularityPort = popularityPort;
        this.clock = clock;
        this.memberLifecycleService = memberLifecycleService;
    }

    @Operation(
            summary = "오디 오디오 도슨트 스토리 목록 조회",
            description = "언어, 카테고리, 행정구역 코드를 기반으로 오디 오디오 스토리 목록과 재생 시간, 커서 페이징 결과를 조회합니다."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "오디오 스토리 목록 조회 성공", content = @Content(schema = @Schema(implementation = com.yrootlab.onmaru.audio.query.OdiiStoryPage.class))),
            @ApiResponse(responseCode = "400", description = "유효하지 않은 요청 또는 만료된 커서", content = @Content(schema = @Schema(implementation = ApiErrorResponse.class))),
            @ApiResponse(responseCode = "503", description = "오디 서비스 일시적 이용 불가", content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    })
    @GetMapping("/api/v1/odii/stories")
    ResponseEntity<?> listStories(
            @Parameter(description = "해설 언어 코드 (ko-KR, en-US 등)", example = "ko-KR")
            @RequestParam(required = false, defaultValue = "ko-KR") String language,
            @Parameter(description = "스토리 카테고리", example = "HISTORIC")
            @RequestParam(required = false) String category,
            @Parameter(description = "행정구역 코드", example = "11110")
            @RequestParam(required = false) String regionCode,
            @Parameter(description = "조회 건수 (기본값 20)", example = "20")
            @RequestParam(required = false, defaultValue = "20") String limit,
            @Parameter(description = "다음 페이지 커서 토큰")
            @RequestParam(required = false) String cursor,
            @Parameter(description = "회원 세션 쿠키 (저장 여부 판별용)", hidden = true)
            @CookieValue(name = SESSION_COOKIE, required = false) String sessionToken,
            HttpServletRequest request) {
        try {
            return ok(queryService.list(new OdiiStoryQuery(
                    language,
                    category,
                    regionCode,
                    parseLimit(limit),
                    cursor,
                    memberId(sessionToken))));
        } catch (OdiiStoryInvalidRequestException exception) {
            return invalidRequest(request, exception.field());
        } catch (OdiiCursorInvalidException exception) {
            return error(ApiErrorCode.CURSOR_INVALID, request, Map.of());
        } catch (OdiiCursorExpiredException exception) {
            return error(ApiErrorCode.CURSOR_EXPIRED, request, Map.of());
        } catch (OdiiStoryUnavailableException exception) {
            return unavailable(request);
        }
    }

    @Operation(
            summary = "오디 오디오 도슨트 상세 및 스크립트 조회",
            description = "오디오 스토리 ID를 기반으로 스트리밍 오디오 URL, 전체 자막/스크립트 텍스트, 장소 매핑 정보를 조회합니다."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "오디오 스토리 상세 조회 성공", content = @Content(schema = @Schema(implementation = com.yrootlab.onmaru.audio.query.OdiiStoryDetail.class))),
            @ApiResponse(responseCode = "404", description = "오디오 스토리를 찾을 수 없음", content = @Content(schema = @Schema(implementation = ApiErrorResponse.class))),
            @ApiResponse(responseCode = "503", description = "오디 서비스 일시적 이용 불가", content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    })
    @GetMapping("/api/v1/odii/stories/{storyId}")
    ResponseEntity<?> storyDetail(
            @Parameter(description = "오디 스토리 고유 ID", example = "story-gyeongbokgung-01")
            @PathVariable String storyId,
            @Parameter(description = "해설 언어 코드", example = "ko-KR")
            @RequestParam(required = false, defaultValue = "ko-KR") String language,
            @Parameter(description = "회원 세션 쿠키 (저장 여부 판별용)", hidden = true)
            @CookieValue(name = SESSION_COOKIE, required = false) String sessionToken,
            HttpServletRequest request) {
        try {
            return ok(queryService.detail(storyId, language, memberId(sessionToken)));
        } catch (OdiiStoryInvalidRequestException exception) {
            return invalidRequest(request, exception.field());
        } catch (OdiiStoryNotFoundException exception) {
            return notFound(request);
        } catch (OdiiStoryUnavailableException exception) {
            return unavailable(request);
        }
    }

    @Operation(
            summary = "오디 오디오 지역 그룹 조회",
            description = "광역 지역 그룹(서울·경기·인천 등)별 활성 오디오 스토리 수를 조회합니다. \"지도로 듣는 이야기\" 지역 탭에 사용합니다."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "지역 그룹 조회 성공", content = @Content(schema = @Schema(implementation = com.yrootlab.onmaru.audio.query.OdiiRegionGroupsPage.class))),
            @ApiResponse(responseCode = "400", description = "유효하지 않은 언어 코드", content = @Content(schema = @Schema(implementation = ApiErrorResponse.class))),
            @ApiResponse(responseCode = "503", description = "오디 서비스 일시적 이용 불가", content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    })
    @GetMapping("/api/v1/odii/regions")
    ResponseEntity<?> regionGroups(
            @Parameter(description = "해설 언어 코드 (ko-KR, en-US 등)", example = "ko-KR")
            @RequestParam(required = false, defaultValue = "ko-KR") String language,
            HttpServletRequest request) {
        try {
            return ok(queryService.regionGroups(language));
        } catch (OdiiStoryInvalidRequestException exception) {
            return invalidRequest(request, exception.field());
        } catch (OdiiStoryUnavailableException exception) {
            return unavailable(request);
        }
    }

    @Hidden
    @GetMapping("/api/v1/audio/regions")
    ResponseEntity<?> regionGroupsCompatibility(
            @RequestParam(required = false, defaultValue = "ko-KR") String language,
            HttpServletRequest request) {
        return regionGroups(language, request);
    }

    @Operation(
            summary = "오디오 스토리 재생 기록",
            description = "오디오 스토리 재생 시작을 기록한다. 이번 주 인기 랭킹(popular-sounds)의 재생 수 신호로 사용된다."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "재생 기록 성공"),
            @ApiResponse(responseCode = "404", description = "스토리를 찾을 수 없음", content = @Content(schema = @Schema(implementation = ApiErrorResponse.class))),
            @ApiResponse(responseCode = "503", description = "오디오 서비스 일시적 이용 불가", content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    })
    @PostMapping("/api/v1/odii/stories/{storyId}/plays")
    ResponseEntity<?> recordPlay(
            @Parameter(description = "오디오 스토리 ID", example = "odii-story-jeonju-hanok-01")
            @PathVariable String storyId,
            HttpServletRequest request) {
        try {
            queryService.savedStory(storyId, Optional.empty());
            popularityPort.recordPlay(storyId, clock.instant());
            return ok(Map.of("schemaVersion", "1.2", "storyId", storyId, "recorded", true));
        } catch (OdiiStoryNotFoundException exception) {
            return notFound(request);
        } catch (OdiiStoryUnavailableException exception) {
            return unavailable(request);
        }
    }

    private ResponseEntity<Object> ok(Object body) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(body);
    }

    private Optional<UUID> memberId(String sessionToken) {
        return memberLifecycleService.currentMember(sessionToken).map(member -> member.id());
    }

    private int parseLimit(String limit) {
        try {
            return Integer.parseInt(limit);
        } catch (NumberFormatException exception) {
            throw new OdiiStoryInvalidRequestException("limit");
        }
    }

    private ResponseEntity<ApiErrorResponse> invalidRequest(HttpServletRequest request, String field) {
        return ResponseEntity.badRequest()
                .cacheControl(CacheControl.noStore())
                .body(new ApiErrorResponse(
                        "1.2",
                        "INVALID_REQUEST",
                        "The requested Odii story query is invalid.",
                        requestId(request),
                        Map.of("field", field)));
    }

    private ResponseEntity<ApiErrorResponse> unavailable(HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .cacheControl(CacheControl.noStore())
                .body(new ApiErrorResponse(
                        "1.2",
                        "SERVICE_UNAVAILABLE",
                        "Odii data is temporarily unavailable.",
                        requestId(request),
                        Map.of("retryAfterMs", 30000)));
    }

    private ResponseEntity<ApiErrorResponse> notFound(HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .cacheControl(CacheControl.noStore())
                .body(new ApiErrorResponse(
                        "1.2",
                        "NOT_FOUND",
                        "The requested Odii story is not available.",
                        requestId(request),
                        Map.of("resourceType", "ODII_STORY")));
    }

    private ResponseEntity<ApiErrorResponse> error(
            ApiErrorCode code,
            HttpServletRequest request,
            Map<String, Object> details) {
        return ResponseEntity.status(code.status())
                .cacheControl(CacheControl.noStore())
                .body(ApiErrorResponse.of(code, requestId(request), details));
    }

    private String requestId(HttpServletRequest request) {
        var fromAttribute = request.getAttribute(RequestIdFilter.ATTRIBUTE);
        if (fromAttribute instanceof String requestId && !requestId.isBlank()) {
            return requestId;
        }
        var fromHeader = request.getHeader(RequestIdFilter.HEADER);
        return fromHeader == null || fromHeader.isBlank() ? UUID.randomUUID().toString() : fromHeader;
    }
}
