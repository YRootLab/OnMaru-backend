package com.yrootlab.onmaru.web.map.region;

import com.yrootlab.onmaru.community.region.VisitReviewRegionInvalidRequestException;
import com.yrootlab.onmaru.community.region.VisitReviewRegionReadService;
import com.yrootlab.onmaru.community.region.VisitReviewRegionUnavailableException;
import com.yrootlab.onmaru.web.common.error.ApiErrorCode;
import com.yrootlab.onmaru.web.common.error.ApiErrorResponse;
import com.yrootlab.onmaru.web.common.error.RequestIdFilter;
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

@RestController
public final class VisitReviewRegionController {

    private final VisitReviewRegionReadService regionReadService;

    VisitReviewRegionController(VisitReviewRegionReadService regionReadService) {
        this.regionReadService = regionReadService;
    }

    @GetMapping("/api/v1/visit-review-regions")
    ResponseEntity<?> listVisitReviewRegions(@RequestParam(required = false) String parentRegionCode, HttpServletRequest request) {
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

    @GetMapping("/api/v1/regions/resolve")
    ResponseEntity<?> resolveRegion(
            @RequestParam double lat,
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
