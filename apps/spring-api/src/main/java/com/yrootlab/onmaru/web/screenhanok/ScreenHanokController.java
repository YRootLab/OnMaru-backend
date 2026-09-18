package com.yrootlab.onmaru.web.screenhanok;

import com.yrootlab.onmaru.catalog.application.query.hanok.HanokListUnavailableException;
import com.yrootlab.onmaru.catalog.screenhanok.ScreenHanokMediaType;
import com.yrootlab.onmaru.catalog.screenhanok.ScreenHanokQueryService;
import com.yrootlab.onmaru.identity.lifecycle.MemberLifecycleService;
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
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Tag(name = "01. 한옥 & 장소 (Hanok & Place)", description = "전국 전통 한옥 및 관광지 목록 조회, 필터링, 상세 정보 API")
@RestController
public final class ScreenHanokController {

    private static final String SESSION_COOKIE = "__Host-onmaru-session";

    private final ScreenHanokQueryService queryService;
    private final MemberLifecycleService memberLifecycleService;

    ScreenHanokController(ScreenHanokQueryService queryService, MemberLifecycleService memberLifecycleService) {
        this.queryService = queryService;
        this.memberLifecycleService = memberLifecycleService;
    }

    @Operation(
            summary = "스크린 속 한옥(K-콘텐츠) 조회",
            description = "실제 촬영/등장이 출처와 함께 확인된 K-드라마·영화·K-POP 뮤직비디오 촬영지 한옥 목록을 조회합니다. "
                    + "AI가 출처(URL)와 함께 매칭한 항목만 게시되며, 사람 검수는 거치지 않습니다(ADR-0010)."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "조회 성공"),
            @ApiResponse(responseCode = "503", description = "카탈로그 서비스 일시적 이용 불가", content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    })
    @GetMapping("/api/v1/hanoks/screen-hanok")
    ResponseEntity<?> screenHanok(
            @Parameter(description = "지역명 필터", example = "충남")
            @RequestParam(required = false) String region,
            @Parameter(description = "매체 유형 필터", example = "K_DRAMA")
            @RequestParam(required = false) String mediaType,
            @Parameter(description = "회원 세션 쿠키 (저장 여부 판별용)", hidden = true)
            @CookieValue(name = SESSION_COOKIE, required = false) String sessionToken,
            HttpServletRequest request) {
        try {
            var items = queryService.list(
                            Optional.ofNullable(region).filter(value -> !value.isBlank()),
                            parseMediaType(mediaType),
                            memberId(sessionToken))
                    .stream()
                    .map(ScreenHanokItemResponse::from)
                    .toList();
            return ResponseEntity.ok()
                    .cacheControl(CacheControl.noStore())
                    .body(new ScreenHanokListResponse(items.size(), items));
        } catch (IllegalArgumentException exception) {
            return error(request, "지원하지 않는 mediaType 값입니다.");
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

    private Optional<ScreenHanokMediaType> parseMediaType(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(ScreenHanokMediaType.valueOf(value));
    }

    private Optional<UUID> memberId(String sessionToken) {
        return memberLifecycleService.currentMember(sessionToken).map(member -> member.id());
    }

    private ResponseEntity<ApiErrorResponse> error(HttpServletRequest request, String message) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .cacheControl(CacheControl.noStore())
                .body(new ApiErrorResponse("1.2", "INVALID_REQUEST", message, requestId(request), Map.of()));
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
