package com.yrootlab.onmaru.web.hanok.list;

import com.yrootlab.onmaru.catalog.application.query.hanok.HanokCursorExpiredException;
import com.yrootlab.onmaru.catalog.application.query.hanok.HanokCursorInvalidException;
import com.yrootlab.onmaru.catalog.application.query.hanok.HanokListCategory;
import com.yrootlab.onmaru.catalog.application.query.hanok.HanokListQuery;
import com.yrootlab.onmaru.catalog.application.query.hanok.HanokListQueryService;
import com.yrootlab.onmaru.catalog.application.query.hanok.HanokListUnavailableException;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Tag(name = "01. 한옥 & 장소 (Hanok & Place)", description = "전국 전통 한옥 및 관광지 목록 조회, 필터링, 상세 정보 API")
@RestController
public final class HanokListController {

    private static final String SESSION_COOKIE = "__Host-onmaru-session";

    private final HanokListQueryService queryService;
    private final MemberLifecycleService memberLifecycleService;

    HanokListController(HanokListQueryService queryService, MemberLifecycleService memberLifecycleService) {
        this.queryService = queryService;
        this.memberLifecycleService = memberLifecycleService;
    }

    @Operation(
            summary = "한옥 목록 조회 및 검색",
            description = "키워드, 행정구역 코드, 카테고리(STAY, EXPERIENCE, HISTORIC), 대표 사진 유무를 기반으로 커서 페이징 방식의 한옥 목록을 조회합니다."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "한옥 목록 조회 성공", content = @Content(schema = @Schema(implementation = com.yrootlab.onmaru.catalog.application.query.hanok.HanokListPage.class))),
            @ApiResponse(responseCode = "400", description = "유효하지 않거나 만료된 페이징 커서", content = @Content(schema = @Schema(implementation = ApiErrorResponse.class))),
            @ApiResponse(responseCode = "503", description = "카탈로그 서비스 일시적 이용 불가", content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    })
    @GetMapping("/api/v1/hanoks")
    ResponseEntity<?> listHanoks(
            @Parameter(description = "검색 키워드 (한옥 이름 또는 설명)", example = "북촌")
            @RequestParam(required = false) String keyword,
            @Parameter(description = "행정구역 코드 (시/도 또는 시/군/구)", example = "11110")
            @RequestParam(required = false) String regionCode,
            @Parameter(description = "한옥 카테고리 (STAY, EXPERIENCE, HISTORIC)", example = "STAY")
            @RequestParam(required = false) String category,
            @Parameter(description = "사진 보유 한옥만 필터링", example = "true")
            @RequestParam(required = false, defaultValue = "false") boolean hasImage,
            @Parameter(description = "조회 개수 (기본값 20, 최대 50)", example = "20")
            @RequestParam(required = false, defaultValue = "20") int limit,
            @Parameter(description = "다음 페이지 조회를 위한 커서 토큰")
            @RequestParam(required = false) String cursor,
            @Parameter(description = "회원 세션 쿠키 (저장 여부 판별용)", hidden = true)
            @CookieValue(name = SESSION_COOKIE, required = false) String sessionToken,
            HttpServletRequest request) {
        try {
            var query = new HanokListQuery(
                    keyword,
                    regionCode,
                    parseCategory(category),
                    hasImage,
                    Math.min(Math.max(limit, 1), 50),
                    cursor,
                    memberId(sessionToken));
            return ResponseEntity.ok()
                    .cacheControl(CacheControl.noStore())
                    .body(queryService.list(query));
        } catch (HanokCursorInvalidException exception) {
            return error(ApiErrorCode.CURSOR_INVALID, request, Map.of());
        } catch (HanokCursorExpiredException exception) {
            return error(ApiErrorCode.CURSOR_EXPIRED, request, Map.of());
        } catch (HanokListUnavailableException exception) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .cacheControl(CacheControl.noStore())
                    .body(new ApiErrorResponse(
                            "1.2",
                            "SERVICE_UNAVAILABLE",
                            "Catalog data is temporarily unavailable.",
                            requestId(request),
                            Map.of("retryAfterMs", 30000)));
        }
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

    private Optional<UUID> memberId(String sessionToken) {
        return memberLifecycleService.currentMember(sessionToken).map(member -> member.id());
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
