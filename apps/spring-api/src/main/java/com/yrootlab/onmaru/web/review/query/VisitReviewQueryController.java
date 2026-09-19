package com.yrootlab.onmaru.web.review.query;

import com.yrootlab.onmaru.community.query.ReviewQueryScope;
import com.yrootlab.onmaru.community.query.VisitReviewCursorExpiredException;
import com.yrootlab.onmaru.community.query.VisitReviewCursorInvalidException;
import com.yrootlab.onmaru.community.query.VisitReviewInvalidRequestException;
import com.yrootlab.onmaru.community.query.VisitReviewQuery;
import com.yrootlab.onmaru.community.query.VisitReviewQueryService;
import com.yrootlab.onmaru.community.query.VisitReviewUnavailableException;
import com.yrootlab.onmaru.identity.lifecycle.MemberLifecycleService;
import com.yrootlab.onmaru.web.common.error.ApiErrorCode;
import com.yrootlab.onmaru.web.common.error.ApiErrorResponse;
import com.yrootlab.onmaru.web.common.error.RequestIdFilter;
import jakarta.servlet.http.HttpServletRequest;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Tag(name = "03. 지도 & 방문 후기 (Map & Reviews)", description = "방문 후기 작성, 조회, 좋아요, 신고, 행정구역별 지도 통계 API")
@RestController
public final class VisitReviewQueryController {

    private static final String SESSION_COOKIE = "__Host-onmaru-session";

    private final VisitReviewQueryService queryService;
    private final MemberLifecycleService memberLifecycleService;

    VisitReviewQueryController(VisitReviewQueryService queryService, MemberLifecycleService memberLifecycleService) {
        this.queryService = queryService;
        this.memberLifecycleService = memberLifecycleService;
    }

    @Operation(
            summary = "방문 후기 목록 조회 (글로벌/지역 범위)",
            description = "스코프(ALL, REGION, MY 등)와 행정구역 코드를 지정하여 커서 페이징 방식의 방문 후기 피드를 조회합니다."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "방문 후기 목록 조회 성공"),
            @ApiResponse(responseCode = "400", description = "유효하지 않은 스코프 또는 커서", content = @Content(schema = @Schema(implementation = ApiErrorResponse.class))),
            @ApiResponse(responseCode = "503", description = "후기 서비스 일시적 이용 불가", content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    })
    @GetMapping("/api/v1/visit-reviews")
    ResponseEntity<?> listVisitReviews(
            @Parameter(description = "조회 범위 스코프 (ALL, REGION, MY)", example = "ALL", required = true)
            @RequestParam String scope,
            @Parameter(description = "행정구역 코드 (스코프가 REGION일 때 필수)", example = "11110")
            @RequestParam(required = false) String regionCode,
            @Parameter(description = "조회 건수 (기본값 20)", example = "20")
            @RequestParam(required = false, defaultValue = "20") int limit,
            @Parameter(description = "다음 페이지 커서 토큰")
            @RequestParam(required = false) String cursor,
            @Parameter(description = "회원 세션 쿠키 (내 좋아요 여부 판별용)", hidden = true)
            @CookieValue(name = SESSION_COOKIE, required = false) String sessionToken,
            HttpServletRequest request) {
        try {
            var query = new VisitReviewQuery(
                    parseScope(scope),
                    normalize(regionCode),
                    null,
                    limit,
                    cursor,
                    memberId(sessionToken));
            return ok(query);
        } catch (VisitReviewInvalidRequestException exception) {
            return validationError(request, exception.field());
        } catch (VisitReviewCursorInvalidException exception) {
            return error(ApiErrorCode.CURSOR_INVALID, request, Map.of());
        } catch (VisitReviewCursorExpiredException exception) {
            return error(ApiErrorCode.CURSOR_EXPIRED, request, Map.of());
        } catch (VisitReviewUnavailableException exception) {
            return unavailable(request);
        }
    }

    @Operation(
            summary = "특정 장소의 방문 후기 목록 조회",
            description = "장소 ID(placeId)에 등록된 방문 후기들을 커서 페이징 방식으로 조회합니다."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "장소별 방문 후기 목록 조회 성공"),
            @ApiResponse(responseCode = "400", description = "유효하지 않은 요청 또는 커서", content = @Content(schema = @Schema(implementation = ApiErrorResponse.class))),
            @ApiResponse(responseCode = "503", description = "후기 서비스 일시적 이용 불가", content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    })
    @GetMapping("/api/v1/places/{placeId}/visit-reviews")
    ResponseEntity<?> listPlaceVisitReviews(
            @Parameter(description = "장소 고유 식별자", example = "place-seoul-bukchon-001")
            @PathVariable String placeId,
            @Parameter(description = "조회 건수 (기본값 20)", example = "20")
            @RequestParam(required = false, defaultValue = "20") int limit,
            @Parameter(description = "다음 페이지 커서 토큰")
            @RequestParam(required = false) String cursor,
            @Parameter(description = "회원 세션 쿠키", hidden = true)
            @CookieValue(name = SESSION_COOKIE, required = false) String sessionToken,
            HttpServletRequest request) {
        try {
            return ok(VisitReviewQuery.place(placeId, limit, cursor, memberId(sessionToken)));
        } catch (VisitReviewInvalidRequestException exception) {
            return validationError(request, exception.field());
        } catch (VisitReviewCursorInvalidException exception) {
            return error(ApiErrorCode.CURSOR_INVALID, request, Map.of());
        } catch (VisitReviewCursorExpiredException exception) {
            return error(ApiErrorCode.CURSOR_EXPIRED, request, Map.of());
        } catch (VisitReviewUnavailableException exception) {
            return unavailable(request);
        }
    }

    private ResponseEntity<?> ok(VisitReviewQuery query) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(queryService.list(query));
    }

    private ReviewQueryScope parseScope(String scope) {
        try {
            return ReviewQueryScope.valueOf(scope);
        } catch (IllegalArgumentException exception) {
            throw new VisitReviewInvalidRequestException("scope");
        }
    }

    private Optional<UUID> memberId(String sessionToken) {
        return memberLifecycleService.currentMember(sessionToken).map(member -> member.id());
    }

    private String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private ResponseEntity<ApiErrorResponse> validationError(HttpServletRequest request, String field) {
        return error(ApiErrorCode.VALIDATION_ERROR, request, Map.of("field", field));
    }

    private ResponseEntity<ApiErrorResponse> unavailable(HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .cacheControl(CacheControl.noStore())
                .body(new ApiErrorResponse(
                        "1.2",
                        "SERVICE_UNAVAILABLE",
                        "Visit review data is temporarily unavailable.",
                        requestId(request),
                        Map.of("retryAfterMs", 30000)));
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
