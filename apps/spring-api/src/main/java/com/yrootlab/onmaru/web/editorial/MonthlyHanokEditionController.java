package com.yrootlab.onmaru.web.editorial;

import com.yrootlab.onmaru.catalog.editorial.MonthlyHanokEditionService;
import com.yrootlab.onmaru.catalog.editorial.MonthlyHanokEditionUnavailableException;
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

import java.time.YearMonth;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Tag(name = "01. 한옥 & 장소 (Hanok & Place)", description = "전국 전통 한옥 및 관광지 목록 조회, 필터링, 상세 정보 API")
@RestController
public final class MonthlyHanokEditionController {

    private static final String SESSION_COOKIE = "__Host-onmaru-session";

    private final MonthlyHanokEditionService service;
    private final MemberLifecycleService memberLifecycleService;

    MonthlyHanokEditionController(MonthlyHanokEditionService service, MemberLifecycleService memberLifecycleService) {
        this.service = service;
        this.memberLifecycleService = memberLifecycleService;
    }

    @Operation(
            summary = "이달의 한옥 에디토리얼 조회",
            description = "특정 연/월(YYYY-MM)의 큐레이션된 이달의 한옥 매거진 에디토리얼 콘텐츠를 조회합니다."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "에디토리얼 조회 성공"),
            @ApiResponse(responseCode = "503", description = "카탈로그 서비스 일시적 이용 불가", content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    })
    @GetMapping("/api/v1/hanoks/monthly")
    ResponseEntity<?> monthly(
            @Parameter(description = "조회 대상 연/월 (YYYY-MM)", example = "2026-09")
            @RequestParam String month,
            @Parameter(description = "회원 세션 쿠키 (저장 여부 판별용)", hidden = true)
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
