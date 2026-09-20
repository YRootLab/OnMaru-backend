package com.yrootlab.onmaru.web.home;

import com.yrootlab.onmaru.audio.query.OdiiCursorExpiredException;
import com.yrootlab.onmaru.audio.query.OdiiCursorInvalidException;
import com.yrootlab.onmaru.audio.query.OdiiStoryInvalidRequestException;
import com.yrootlab.onmaru.audio.query.OdiiStoryQuery;
import com.yrootlab.onmaru.audio.query.OdiiStoryQueryService;
import com.yrootlab.onmaru.audio.query.OdiiStoryUnavailableException;
import com.yrootlab.onmaru.catalog.application.query.hanok.HanokCursorExpiredException;
import com.yrootlab.onmaru.catalog.application.query.hanok.HanokCursorInvalidException;
import com.yrootlab.onmaru.catalog.application.query.hanok.HanokListCategory;
import com.yrootlab.onmaru.catalog.application.query.hanok.HanokListQuery;
import com.yrootlab.onmaru.catalog.application.query.hanok.HanokListQueryService;
import com.yrootlab.onmaru.catalog.application.query.hanok.HanokListUnavailableException;
import com.yrootlab.onmaru.community.region.VisitReviewRegionInvalidRequestException;
import com.yrootlab.onmaru.community.region.VisitReviewRegionReadService;
import com.yrootlab.onmaru.community.region.VisitReviewRegionUnavailableException;
import com.yrootlab.onmaru.identity.lifecycle.MemberLifecycleService;
import com.yrootlab.onmaru.web.common.error.ApiErrorCode;
import com.yrootlab.onmaru.web.common.error.ApiErrorResponse;
import com.yrootlab.onmaru.web.common.error.RequestIdFilter;
import io.swagger.v3.oas.annotations.Hidden;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Tag(name = "00. 홈 (Home)", description = "홈 화면에서 사용하는 큐레이션, 오디오, 지역 집계 API")
@RestController
public final class HomeController {

    private static final String SESSION_COOKIE = "__Host-onmaru-session";

    private final HanokListQueryService hanokListQueryService;
    private final OdiiStoryQueryService odiiStoryQueryService;
    private final VisitReviewRegionReadService regionReadService;
    private final MemberLifecycleService memberLifecycleService;

    HomeController(
            HanokListQueryService hanokListQueryService,
            OdiiStoryQueryService odiiStoryQueryService,
            VisitReviewRegionReadService regionReadService,
            MemberLifecycleService memberLifecycleService) {
        this.hanokListQueryService = hanokListQueryService;
        this.odiiStoryQueryService = odiiStoryQueryService;
        this.regionReadService = regionReadService;
        this.memberLifecycleService = memberLifecycleService;
    }

    @Operation(summary = "홈 큐레이션 코스 조회", description = "게시된 장소 목록을 홈 큐레이션 카드로 조회합니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "큐레이션 코스 조회 성공"),
            @ApiResponse(responseCode = "503", description = "카탈로그 서비스 일시적 이용 불가",
                    content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    })
    @GetMapping("/api/v1/home/curated-courses")
    ResponseEntity<?> curatedCourses(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String regionCode,
            @RequestParam(required = false) String category,
            @RequestParam(required = false, defaultValue = "false") boolean hasImage,
            @RequestParam(required = false, defaultValue = "20") int limit,
            @RequestParam(required = false) String cursor,
            @CookieValue(name = SESSION_COOKIE, required = false) String sessionToken,
            HttpServletRequest request) {
        try {
            return ok(hanokListQueryService.list(new HanokListQuery(
                    keyword,
                    regionCode,
                    parseCategory(category),
                    hasImage,
                    Math.min(Math.max(limit, 1), 50),
                    cursor,
                    memberId(sessionToken))));
        } catch (HanokCursorInvalidException exception) {
            return error(ApiErrorCode.CURSOR_INVALID, request, Map.of());
        } catch (HanokCursorExpiredException exception) {
            return error(ApiErrorCode.CURSOR_EXPIRED, request, Map.of());
        } catch (HanokListUnavailableException exception) {
            return unavailable(request, "Catalog data is temporarily unavailable.");
        }
    }

    @Hidden
    @GetMapping("/api/home/curated-courses")
    ResponseEntity<?> curatedCoursesCompatibility(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String regionCode,
            @RequestParam(required = false) String category,
            @RequestParam(required = false, defaultValue = "false") boolean hasImage,
            @RequestParam(required = false, defaultValue = "20") int limit,
            @RequestParam(required = false) String cursor,
            @CookieValue(name = SESSION_COOKIE, required = false) String sessionToken,
            HttpServletRequest request) {
        return curatedCourses(keyword, regionCode, category, hasImage, limit, cursor, sessionToken, request);
    }

    @Operation(summary = "홈 인기 오디오 조회", description = "활성화된 Odii 오디오 스토리를 홈 인기 사운드 카드로 조회합니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "인기 오디오 조회 성공"),
            @ApiResponse(responseCode = "503", description = "오디오 서비스 일시적 이용 불가",
                    content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    })
    @GetMapping("/api/v1/home/trending-sounds")
    ResponseEntity<?> trendingSounds(
            @RequestParam(required = false, defaultValue = "ko-KR") String language,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String regionCode,
            @RequestParam(required = false, defaultValue = "20") String limit,
            @RequestParam(required = false) String cursor,
            @CookieValue(name = SESSION_COOKIE, required = false) String sessionToken,
            HttpServletRequest request) {
        try {
            return ok(odiiStoryQueryService.list(new OdiiStoryQuery(
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
            return unavailable(request, "Odii data is temporarily unavailable.");
        }
    }

    @Hidden
    @GetMapping("/api/home/trending-sounds")
    ResponseEntity<?> trendingSoundsCompatibility(
            @RequestParam(required = false, defaultValue = "ko-KR") String language,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String regionCode,
            @RequestParam(required = false, defaultValue = "20") String limit,
            @RequestParam(required = false) String cursor,
            @CookieValue(name = SESSION_COOKIE, required = false) String sessionToken,
            HttpServletRequest request) {
        return trendingSounds(language, category, regionCode, limit, cursor, sessionToken, request);
    }

    @Operation(summary = "홈 인기 지역 조회", description = "공개 방문 후기 집계를 기준으로 지역별 인기 지표를 조회합니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "인기 지역 조회 성공"),
            @ApiResponse(responseCode = "503", description = "지역 서비스 일시적 이용 불가",
                    content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    })
    @GetMapping("/api/v1/home/popular-regions")
    ResponseEntity<?> popularRegions(
            @Parameter(description = "상위 시/도 지역 코드") @RequestParam(required = false) String parentRegionCode,
            HttpServletRequest request) {
        try {
            return ok(regionReadService.listRegions(parentRegionCode));
        } catch (VisitReviewRegionInvalidRequestException exception) {
            return invalidRequest(request, exception.field());
        } catch (VisitReviewRegionUnavailableException exception) {
            return unavailable(request, "Region data is temporarily unavailable.");
        }
    }

    @Hidden
    @GetMapping("/api/home/popular-regions")
    ResponseEntity<?> popularRegionsCompatibility(
            @RequestParam(required = false) String parentRegionCode,
            HttpServletRequest request) {
        return popularRegions(parentRegionCode, request);
    }

    private HanokListCategory parseCategory(String category) {
        if (category == null || category.isBlank()) {
            return null;
        }
        try {
            return HanokListCategory.valueOf(category);
        } catch (IllegalArgumentException exception) {
            throw new HanokCursorInvalidException();
        }
    }

    private int parseLimit(String limit) {
        try {
            return Integer.parseInt(limit);
        } catch (NumberFormatException exception) {
            throw new OdiiStoryInvalidRequestException("limit");
        }
    }

    private Optional<UUID> memberId(String sessionToken) {
        return memberLifecycleService.currentMember(sessionToken).map(member -> member.id());
    }

    private ResponseEntity<Object> ok(Object body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }

    private ResponseEntity<ApiErrorResponse> invalidRequest(HttpServletRequest request, String field) {
        return ResponseEntity.badRequest()
                .cacheControl(CacheControl.noStore())
                .body(new ApiErrorResponse(
                        "1.2", "INVALID_REQUEST", "The requested home query is invalid.",
                        requestId(request), Map.of("field", field)));
    }

    private ResponseEntity<ApiErrorResponse> error(
            ApiErrorCode code, HttpServletRequest request, Map<String, Object> details) {
        return ResponseEntity.status(code.status())
                .cacheControl(CacheControl.noStore())
                .body(ApiErrorResponse.of(code, requestId(request), details));
    }

    private ResponseEntity<ApiErrorResponse> unavailable(HttpServletRequest request, String message) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .cacheControl(CacheControl.noStore())
                .body(new ApiErrorResponse(
                        "1.2", "SERVICE_UNAVAILABLE", message,
                        requestId(request), Map.of("retryAfterMs", 30000)));
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
