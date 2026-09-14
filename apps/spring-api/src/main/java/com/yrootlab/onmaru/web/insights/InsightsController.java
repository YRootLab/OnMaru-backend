package com.yrootlab.onmaru.web.insights;

import com.yrootlab.onmaru.insights.query.InsightsQueryService;
import com.yrootlab.onmaru.web.common.error.ApiErrorCode;
import com.yrootlab.onmaru.web.common.error.ApiErrorResponse;
import com.yrootlab.onmaru.web.common.error.RequestIdFilter;
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

@RestController
public final class InsightsController {

    private final InsightsQueryService queryService;

    InsightsController(InsightsQueryService queryService) {
        this.queryService = queryService;
    }

    @GetMapping("/api/v1/insights/observations")
    ResponseEntity<?> listObservations(
            @RequestParam String regionCode,
            @RequestParam(required = false) String metric,
            @RequestParam(required = false) String from,
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

    @GetMapping("/api/v1/insights/heatmap")
    ResponseEntity<?> heatmap(
            @RequestParam(required = false) String regionCode,
            @RequestParam String date,
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
