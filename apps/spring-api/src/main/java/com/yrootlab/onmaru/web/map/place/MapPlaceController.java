package com.yrootlab.onmaru.web.map.place;

import com.yrootlab.onmaru.catalog.application.query.spatial.MapBoundingBox;
import com.yrootlab.onmaru.catalog.application.query.spatial.MapPlaceInvalidRequestException;
import com.yrootlab.onmaru.catalog.application.query.spatial.MapPlaceQuery;
import com.yrootlab.onmaru.catalog.application.query.spatial.MapPlaceQueryService;
import com.yrootlab.onmaru.catalog.application.query.spatial.MapPlaceUnavailableException;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Tag(name = "01. 한옥 & 장소 (Hanok & Place)", description = "전국 전통 한옥 및 관광지 목록 조회, 필터링, 상세 정보 API")
@RestController
public final class MapPlaceController {

    private static final String SESSION_COOKIE = "__Host-onmaru-session";

    private final MapPlaceQueryService queryService;
    private final MemberLifecycleService memberLifecycleService;

    MapPlaceController(MapPlaceQueryService queryService, MemberLifecycleService memberLifecycleService) {
        this.queryService = queryService;
        this.memberLifecycleService = memberLifecycleService;
    }

    @Operation(
            summary = "지도 뷰포트/영역 기반 장소 검색",
            description = "지도의 바운딩 박스(minLng,minLat,maxLng,maxLat) 또는 중심점(lat, lng, radius) 기반으로 화면 내 한옥 및 관광지 목록을 조회합니다."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "지도 장소 목록 조회 성공"),
            @ApiResponse(responseCode = "400", description = "유효하지 않은 좌표 바운딩 박스 파라미터", content = @Content(schema = @Schema(implementation = ApiErrorResponse.class))),
            @ApiResponse(responseCode = "503", description = "카탈로그 서비스 일시적 이용 불가", content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    })
    @GetMapping("/api/v1/map/places")
    ResponseEntity<?> listMapPlaces(
            @Parameter(description = "응답 언어 (기본값 ko-KR)", example = "ko-KR")
            @RequestParam(required = false, defaultValue = "ko-KR") String language,
            @Parameter(description = "행정구역 코드 (시/도 또는 시/군/구)", example = "11110")
            @RequestParam(required = false) String regionCode,
            @Parameter(description = "지도 바운딩 박스 (minLng,minLat,maxLng,maxLat)", example = "126.97,37.56,127.01,37.60")
            @RequestParam(required = false) String bbox,
            @Parameter(description = "중심 위도 (WGS84)", example = "37.58")
            @RequestParam(required = false) Double lat,
            @Parameter(description = "중심 경도 (WGS84)", example = "126.98")
            @RequestParam(required = false) Double lng,
            @Parameter(description = "검색 반경 (미터 단위)", example = "3000")
            @RequestParam(required = false) Integer radius,
            @Parameter(description = "장소 카테고리 (HANOK, TOURIST, RESTAURANT 등)", example = "HANOK")
            @RequestParam(required = false) String category,
            @Parameter(description = "최대 조회 건수 (기본값 20, 최대 100)", example = "20")
            @RequestParam(required = false, defaultValue = "20") int limit,
            @Parameter(description = "회원 세션 쿠키 (저장 여부 판별용)", hidden = true)
            @CookieValue(name = SESSION_COOKIE, required = false) String sessionToken,
            HttpServletRequest request) {
        try {
            var query = new MapPlaceQuery(
                    language,
                    normalize(regionCode),
                    parseBbox(bbox),
                    lat,
                    lng,
                    radius,
                    normalize(category),
                    limit,
                    memberId(sessionToken));
            return ResponseEntity.ok()
                    .cacheControl(CacheControl.noStore())
                    .body(queryService.list(query));
        } catch (MapPlaceInvalidRequestException exception) {
            return invalidRequest(request, exception.field());
        } catch (MapPlaceUnavailableException exception) {
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

    private MapBoundingBox parseBbox(String bbox) {
        if (bbox == null || bbox.isBlank()) {
            return null;
        }
        var parts = bbox.split(",", -1);
        if (parts.length != 4) {
            throw new MapPlaceInvalidRequestException("bbox");
        }
        try {
            return new MapBoundingBox(
                    Double.parseDouble(parts[0]),
                    Double.parseDouble(parts[1]),
                    Double.parseDouble(parts[2]),
                    Double.parseDouble(parts[3]));
        } catch (NumberFormatException exception) {
            throw new MapPlaceInvalidRequestException("bbox");
        }
    }

    private Optional<UUID> memberId(String sessionToken) {
        return memberLifecycleService.currentMember(sessionToken).map(member -> member.id());
    }

    private String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private ResponseEntity<ApiErrorResponse> invalidRequest(HttpServletRequest request, String field) {
        return ResponseEntity.badRequest()
                .cacheControl(CacheControl.noStore())
                .body(new ApiErrorResponse(
                        "1.2",
                        "INVALID_REQUEST",
                        "The requested map bounds are invalid.",
                        requestId(request),
                        Map.of("field", field)));
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
