package com.yrootlab.onmaru.web.insights;

import com.yrootlab.onmaru.insights.query.InsightsQueryService;
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
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Map;
import java.util.UUID;

@Tag(name = "03. 지도 & 방문 후기 (Map & Reviews)", description = "방문 후기 작성, 조회, 좋아요, 신고, 행정구역별 지도 통계 API")
@RestController
public final class InsightsController {

    private final InsightsQueryService queryService;

    InsightsController(InsightsQueryService queryService) {
        this.queryService = queryService;
    }

    @Operation(
            summary = "지역별 관측 데이터 및 방문 메트릭 시계열 조회",
            description = "특정 행정구역(regionCode)의 기간별(from ~ to) 방문 통계, 체류 시간, 선호도 등 관측 지표 시계열 목록을 조회합니다."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "관측 데이터 조회 성공"),
            @ApiResponse(responseCode = "400", description = "유효하지 않은 날짜 또는 파라미터", content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    })
    @GetMapping("/api/v1/insights/observations")
    ResponseEntity<?> listObservations(
            @Parameter(description = "행정구역 코드", example = "11110", required = true)
            @RequestParam String regionCode,
            @Parameter(description = "메트릭 유형 (VISIT_COUNT, REVIEW_COUNT 등)", example = "VISIT_COUNT")
            @RequestParam(required = false) String metric,
            @Parameter(description = "시작일 (YYYY-MM-DD)", example = "2026-09-01")
            @RequestParam(required = false) String from,
            @Parameter(description = "종료일 (YYYY-MM-DD)", example = "2026-09-18")
            @RequestParam(required = false) String to,
            HttpServletRequest request) {
        try {
            return ResponseEntity.ok()
                    .cacheControl(CacheControl.noStore())
                    .body(queryService.listObservations(
                            regionCode,
                            normalizeMetric(metric),
                            parseOptionalDate(from, "from"),
                            parseOptionalDate(to, "to")));
        } catch (InsightsInvalidRequestException exception) {
            return validationError(request, exception.field());
        }
    }

    @Operation(
            summary = "지도 히트맵 통계 지표 조회",
            description = "특정 기준일자(date)와 지역 기준의 지도 히트맵 집계 데이터를 조회합니다."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "히트맵 데이터 조회 성공"),
            @ApiResponse(responseCode = "400", description = "유효하지 않은 날짜 또는 파라미터", content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    })
    @GetMapping("/api/v1/insights/heatmap")
    ResponseEntity<?> heatmap(
            @Parameter(description = "행정구역 코드 (미지정 시 전국)", example = "11")
            @RequestParam(required = false) String regionCode,
            @Parameter(description = "기준 일자 (YYYY-MM-DD)", example = "2026-09-18", required = true)
            @RequestParam String date,
            @Parameter(description = "메트릭 유형", example = "VISIT_COUNT")
            @RequestParam(required = false) String metric,
            HttpServletRequest request) {
        try {
            return ResponseEntity.ok()
                    .cacheControl(CacheControl.noStore())
                    .body(queryService.heatmap(regionCode, parseRequiredDate(date, "date"), normalizeMetric(metric)));
        } catch (InsightsInvalidRequestException exception) {
            return validationError(request, exception.field());
        }
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    ResponseEntity<ApiErrorResponse> missingParameter(MissingServletRequestParameterException exception, HttpServletRequest request) {
        return validationError(request, exception.getParameterName());
    }

    private String normalizeMetric(String metric) {
        return metric == null || metric.isBlank() ? null : metric.trim();
    }

    private LocalDate parseOptionalDate(String value, String field) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return parseRequiredDate(value, field);
    }

    private LocalDate parseRequiredDate(String value, String field) {
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException exception) {
            throw new InsightsInvalidRequestException(field);
        }
    }

    private ResponseEntity<ApiErrorResponse> validationError(HttpServletRequest request, String field) {
        return ResponseEntity.status(ApiErrorCode.VALIDATION_ERROR.status())
                .cacheControl(CacheControl.noStore())
                .body(ApiErrorResponse.of(ApiErrorCode.VALIDATION_ERROR, requestId(request), Map.of("field", field)));
    }

    private String requestId(HttpServletRequest request) {
        var fromAttribute = request.getAttribute(RequestIdFilter.ATTRIBUTE);
        if (fromAttribute instanceof String requestId && !requestId.isBlank()) {
            return requestId;
        }
        var fromHeader = request.getHeader(RequestIdFilter.HEADER);
        return fromHeader == null || fromHeader.isBlank() ? UUID.randomUUID().toString() : fromHeader;
    }

    private static final class InsightsInvalidRequestException extends RuntimeException {

        private InsightsInvalidRequestException(String field) {
            super(field);
        }

        private String field() {
            return getMessage();
        }
    }
}
