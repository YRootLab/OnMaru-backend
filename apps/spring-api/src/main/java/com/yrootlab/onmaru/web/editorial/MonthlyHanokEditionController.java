package com.yrootlab.onmaru.web.editorial;

import com.yrootlab.onmaru.catalog.editorial.MonthlyHanokEditionService;
import com.yrootlab.onmaru.catalog.editorial.MonthlyHanokEditionUnavailableException;
import com.yrootlab.onmaru.identity.lifecycle.MemberLifecycleService;
import com.yrootlab.onmaru.web.common.error.ApiErrorResponse;
import com.yrootlab.onmaru.web.common.error.RequestIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.YearMonth;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@RestController
public final class MonthlyHanokEditionController {

    private static final String SESSION_COOKIE = "__Host-onmaru-session";

    private final MonthlyHanokEditionService service;
    private final MemberLifecycleService memberLifecycleService;

    MonthlyHanokEditionController(MonthlyHanokEditionService service, MemberLifecycleService memberLifecycleService) {
        this.service = service;
        this.memberLifecycleService = memberLifecycleService;
    }

    @GetMapping("/api/v1/hanoks/monthly")
    ResponseEntity<?> monthly(
            @RequestParam String month,
            @CookieValue(name = SESSION_COOKIE, required = false) String sessionToken,
            HttpServletRequest request) {
        try {
            return ResponseEntity.ok()
                    .cacheControl(CacheControl.noStore())
                    .body(service.find(YearMonth.parse(month), memberId(sessionToken)).orElseThrow());
        } catch (MonthlyHanokEditionUnavailableException exception) {
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

    private Optional<UUID> memberId(String sessionToken) {
        return memberLifecycleService.currentMember(sessionToken).map(member -> member.id());
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
