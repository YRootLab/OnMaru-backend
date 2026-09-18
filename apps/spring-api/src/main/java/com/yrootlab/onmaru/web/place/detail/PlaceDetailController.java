package com.yrootlab.onmaru.web.place.detail;

import com.yrootlab.onmaru.catalog.application.query.detail.PlaceDetailQueryService;
import com.yrootlab.onmaru.catalog.application.query.detail.PlaceDetailUnavailableException;
import com.yrootlab.onmaru.identity.lifecycle.MemberLifecycleService;
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
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Tag(name = "01. 한옥 & 장소 (Hanok & Place)", description = "전국 전통 한옥 및 관광지 목록 조회, 필터링, 상세 정보 API")
@RestController
public final class PlaceDetailController {

    private static final String SESSION_COOKIE = "__Host-onmaru-session";

    private final PlaceDetailQueryService queryService;
    private final MemberLifecycleService memberLifecycleService;

    PlaceDetailController(PlaceDetailQueryService queryService, MemberLifecycleService memberLifecycleService) {
        this.queryService = queryService;
        this.memberLifecycleService = memberLifecycleService;
    }

    @Operation(
            summary = "관광지/장소 표준 상세 조회",
            description = "장소 ID(placeId)를 기반으로 기본 정보, 위치 좌표, 개요, 대표 사진, Odii 오디오 매핑 정보를 포함한 상세 정보를 조회합니다."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "장소 상세 조회 성공"),
            @ApiResponse(responseCode = "404", description = "장소를 찾을 수 없음", content = @Content(schema = @Schema(implementation = ApiErrorResponse.class))),
            @ApiResponse(responseCode = "503", description = "카탈로그 서비스 일시적 이용 불가", content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    })
    @GetMapping("/api/v1/places/{placeId}")
    ResponseEntity<?> canonicalPlace(
            @Parameter(description = "장소 고유 식별자", example = "place-seoul-bukchon-001")
            @PathVariable String placeId,
            @Parameter(description = "회원 세션 쿠키 (저장 여부 판별용)", hidden = true)
            @CookieValue(name = SESSION_COOKIE, required = false) String sessionToken,
            HttpServletRequest request) {
        try {
            return queryService.findCanonicalPlace(placeId, memberId(sessionToken))
                    .<ResponseEntity<?>>map(detail -> ResponseEntity.ok()
                            .cacheControl(CacheControl.noStore())
                            .body(detail))
                    .orElseGet(() -> placeNotFound(request));
        } catch (PlaceDetailUnavailableException exception) {
            return serviceUnavailable(request);
        }
    }

    @Operation(
            summary = "한옥 전용 상세 정보 조회",
            description = "한옥 ID를 기반으로 건축 양식, 숙박/체험 정보, 편의시설, 에디토리얼 태그를 포함한 한옥 특화 상세 정보를 조회합니다."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "한옥 상세 조회 성공"),
            @ApiResponse(responseCode = "404", description = "한옥을 찾을 수 없음", content = @Content(schema = @Schema(implementation = ApiErrorResponse.class))),
            @ApiResponse(responseCode = "503", description = "카탈로그 서비스 일시적 이용 불가", content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    })
    @GetMapping("/api/v1/hanoks/{placeId}")
    ResponseEntity<?> hanok(
            @Parameter(description = "한옥 고유 식별자", example = "hanok-jeonju-hakindang-001")
            @PathVariable String placeId,
            @Parameter(description = "회원 세션 쿠키 (저장 여부 판별용)", hidden = true)
            @CookieValue(name = SESSION_COOKIE, required = false) String sessionToken,
            HttpServletRequest request) {
        try {
            return queryService.findHanok(placeId, memberId(sessionToken))
                    .<ResponseEntity<?>>map(detail -> ResponseEntity.ok()
                            .cacheControl(CacheControl.noStore())
                            .body(detail))
                    .orElseGet(() -> placeNotFound(request));
        } catch (PlaceDetailUnavailableException exception) {
            return serviceUnavailable(request);
        }
    }

    private Optional<UUID> memberId(String sessionToken) {
        return memberLifecycleService.currentMember(sessionToken).map(member -> member.id());
    }

    private ResponseEntity<ApiErrorResponse> placeNotFound(HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .cacheControl(CacheControl.noStore())
                .body(new ApiErrorResponse(
                        "1.2",
                        "NOT_FOUND",
                        "The requested place is not available.",
                        requestId(request),
                        Map.of("resourceType", "PLACE")));
    }

    private ResponseEntity<ApiErrorResponse> serviceUnavailable(HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .cacheControl(CacheControl.noStore())
                .body(new ApiErrorResponse(
                        "1.2",
                        "SERVICE_UNAVAILABLE",
                        "Catalog data is temporarily unavailable.",
                        requestId(request),
                        Map.of("retryAfterMs", 30000)));
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
