package com.yrootlab.onmaru.web.map.region;

import com.yrootlab.onmaru.community.region.VisitReviewRegionInvalidRequestException;
import com.yrootlab.onmaru.community.region.VisitReviewRegionReadService;
import com.yrootlab.onmaru.community.region.VisitReviewRegionUnavailableException;
import com.yrootlab.onmaru.web.common.error.ApiErrorCode;
import com.yrootlab.onmaru.web.common.error.ApiErrorResponse;
import com.yrootlab.onmaru.web.common.error.RequestIdFilter;
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
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.Map;
import java.util.UUID;

@Tag(name = "03. 지도 & 방문 후기 (Map & Reviews)", description = "방문 후기 작성, 조회, 좋아요, 신고, 행정구역별 지도 통계 API")
@RestController
public final class VisitReviewRegionController {

    private final VisitReviewRegionReadService regionReadService;

    VisitReviewRegionController(VisitReviewRegionReadService regionReadService) {
        this.regionReadService = regionReadService;
    }

    @Operation(
            summary = "1.2 행정구역 목록 및 방문 후기 통계 조회",
            description = "시/도(1단계) 또는 특정 시/도 하위의 시/군/구(2단계) 행정구역 목록과 각 구역별 누적 방문 후기 건수/지도 메트릭을 조회합니다."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "행정구역 통계 조회 성공"),
            @ApiResponse(responseCode = "400", description = "유효하지 않은 부모 행정구역 코드", content = @Content(schema = @Schema(implementation = ApiErrorResponse.class))),
            @ApiResponse(responseCode = "503", description = "행정구역 서비스 일시적 이용 불가", content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    })
    @GetMapping("/api/v1/visit-review-regions")
    ResponseEntity<?> listVisitReviewRegions(
            @Parameter(description = "상위 시/도 행정구역 코드 (미입력 시 전국 17개 광역시/도 목록 반환)", example = "11")
            @RequestParam(required = false) String parentRegionCode,
            HttpServletRequest request) {
        try {
            return ResponseEntity.ok()
                    .cacheControl(CacheControl.noStore())
                    .body(regionReadService.listRegions(parentRegionCode));
        } catch (VisitReviewRegionInvalidRequestException exception) {
            return validationError(request, exception.field());
        } catch (VisitReviewRegionUnavailableException exception) {
            return unavailable(request);
        }
    }

    @Operation(
            summary = "GPS 좌표 기반 행정구역 역지오코딩 해석 (Point-in-Polygon)",
            description = "위도(lat)와 경도(lng) 좌표가 속한 1.2 행정구역(시/도, 시/군/구) 코드 및 명칭을 조회합니다."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "행정구역 해석 성공"),
            @ApiResponse(responseCode = "400", description = "유효하지 않은 좌표 값", content = @Content(schema = @Schema(implementation = ApiErrorResponse.class))),
            @ApiResponse(responseCode = "503", description = "행정구역 서비스 일시적 이용 불가", content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    })
    @GetMapping("/api/v1/regions/resolve")
    ResponseEntity<?> resolveRegion(
            @Parameter(description = "위도 (WGS84)", example = "37.5665", required = true)
            @RequestParam double lat,
            @Parameter(description = "경도 (WGS84)", example = "126.9780", required = true)
            @RequestParam double lng,
            HttpServletRequest request) {
        try {
            return ResponseEntity.ok()
                    .cacheControl(CacheControl.noStore())
                    .body(regionReadService.resolve(lat, lng));
        } catch (VisitReviewRegionInvalidRequestException exception) {
            return validationError(request, exception.field());
        } catch (VisitReviewRegionUnavailableException exception) {
            return unavailable(request);
        }
    }

    @ExceptionHandler({MissingServletRequestParameterException.class, MethodArgumentTypeMismatchException.class})
    ResponseEntity<ApiErrorResponse> invalidRequestParameter(Exception exception, HttpServletRequest request) {
        String field = switch (exception) {
            case MissingServletRequestParameterException missing -> missing.getParameterName();
            case MethodArgumentTypeMismatchException mismatch -> mismatch.getName();
            default -> "request";
        };
        return validationError(request, field);
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
                        "Region boundary data is temporarily unavailable.",
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
