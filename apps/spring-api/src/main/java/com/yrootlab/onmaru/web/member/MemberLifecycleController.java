package com.yrootlab.onmaru.web.member;

import tools.jackson.databind.JsonNode;
import com.yrootlab.onmaru.identity.lifecycle.MemberLifecycleService;
import com.yrootlab.onmaru.identity.lifecycle.MemberSessionRequiredException;
import com.yrootlab.onmaru.identity.lifecycle.MemberSummary;
import com.yrootlab.onmaru.identity.profile.MemberProfile;
import com.yrootlab.onmaru.identity.profile.MemberProfileInvalidException;
import com.yrootlab.onmaru.identity.profile.MemberProfilePatch;
import com.yrootlab.onmaru.identity.profile.MemberProfileService;
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
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.Set;

@Tag(name = "05. 개인화 & 타임라인 (Saved & Timeline)", description = "북마크/저장한 장소 및 오디오 도슨트, 내 활동 타임라인 및 회원 프로필 API")
@RestController
public final class MemberLifecycleController {

    private static final String SESSION_COOKIE = "__Host-onmaru-session";

    private final MemberLifecycleService lifecycleService;
    private final MemberProfileService profileService;
    private final Clock clock;

    MemberLifecycleController(
            MemberLifecycleService lifecycleService,
            MemberProfileService profileService,
            Clock clock) {
        this.lifecycleService = lifecycleService;
        this.profileService = profileService;
        this.clock = clock;
    }

    @Operation(
            summary = "현재 로그인한 내 프로필 조회",
            description = "세션 쿠키 기반으로 현재 인증된 회원의 ID 및 닉네임 정보를 조회합니다."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "회원 정보 조회 성공", content = @Content(schema = @Schema(implementation = MemberMeResponse.class))),
            @ApiResponse(responseCode = "401", description = "로그인 세션 필요", content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    })
    @GetMapping("/api/v1/members/me")
    ResponseEntity<?> currentMember(
            @Parameter(description = "회원 세션 쿠키", hidden = true)
            @CookieValue(name = SESSION_COOKIE, required = false) String sessionToken,
            HttpServletRequest request) {
        return lifecycleService.currentMember(sessionToken)
                .<ResponseEntity<?>>map(member -> ResponseEntity.ok()
                        .cacheControl(CacheControl.noStore())
                        .body(MemberMeResponse.from(member)))
                .orElseGet(() -> authRequired(request));
    }

    @Operation(
            summary = "내 익명 프로필 수정",
            description = "표시 이름, 온니 캐릭터, 배경 중 전달한 값만 수정합니다."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "프로필 수정 성공", content = @Content(schema = @Schema(implementation = MemberMeResponse.class))),
            @ApiResponse(responseCode = "400", description = "프로필 입력값 오류", content = @Content(schema = @Schema(implementation = ApiErrorResponse.class))),
            @ApiResponse(responseCode = "401", description = "로그인 세션 필요", content = @Content(schema = @Schema(implementation = ApiErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "CSRF 검증 실패", content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    })
    @PatchMapping("/api/v1/members/me")
    ResponseEntity<?> updateCurrentMember(
            @Parameter(description = "회원 세션 쿠키", hidden = true)
            @CookieValue(name = SESSION_COOKIE, required = false) String sessionToken,
            @RequestBody(required = false) JsonNode body,
            HttpServletRequest request) {
        var current = lifecycleService.currentMember(sessionToken);
        if (current.isEmpty()) {
            return authRequired(request);
        }
        try {
            var patch = profilePatch(body);
            return profileService.updateActiveProfile(current.get().id(), patch, clock.instant())
                    .<ResponseEntity<?>>map(profile -> ResponseEntity.ok()
                            .cacheControl(CacheControl.noStore())
                            .body(MemberMeResponse.from(profile)))
                    .orElseGet(() -> authRequired(request));
        } catch (MemberProfileInvalidException exception) {
            return validationError(request, exception.field());
        }
    }

    @Operation(
            summary = "로그아웃",
            description = "현재 활성화된 회원 세션을 만료시키고 세션 쿠키를 삭제합니다."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "로그아웃 완료 및 쿠키 파기")
    })
    @PostMapping("/api/v1/auth/logout")
    ResponseEntity<Void> logout(
            @Parameter(description = "회원 세션 쿠키", hidden = true)
            @CookieValue(name = SESSION_COOKIE, required = false) String sessionToken,
            @RequestBody(required = false) Map<String, Object> ignored) {
        lifecycleService.logout(sessionToken);
        return ResponseEntity.noContent()
                .cacheControl(CacheControl.noStore())
                .header(HttpHeaders.SET_COOKIE, expireSessionCookie().toString())
                .build();
    }

    @Operation(
            summary = "회원 탈퇴 및 계정 삭제 요청",
            description = "회원 탈퇴를 접수하고 세션을 즉시 만료시키며 비식별화/삭제 프로세스를 시작합니다."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "202", description = "회원 탈퇴 요청 접수 완료", content = @Content(schema = @Schema(implementation = MemberDeletingStatusResponse.class))),
            @ApiResponse(responseCode = "401", description = "로그인 세션 필요", content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    })
    @DeleteMapping("/api/v1/members/me")
    ResponseEntity<?> deleteCurrentMember(
            @Parameter(description = "회원 세션 쿠키", hidden = true)
            @CookieValue(name = SESSION_COOKIE, required = false) String sessionToken,
            HttpServletRequest request) {
        try {
            var result = lifecycleService.requestDeletion(sessionToken);
            return ResponseEntity.accepted()
                    .cacheControl(CacheControl.noStore())
                    .header(HttpHeaders.SET_COOKIE, expireSessionCookie().toString())
                    .body(new MemberDeletingStatusResponse("1.2", result.status().name()));
        } catch (MemberSessionRequiredException exception) {
            return authRequired(request);
        }
    }

    private ResponseEntity<Map<String, Object>> authRequired(HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .cacheControl(CacheControl.noStore())
                .body(Map.of(
                        "schemaVersion", "1.2",
                        "code", "AUTH_REQUIRED",
                        "message", "Authentication is required.",
                        "requestId", requestId(request),
                        "details", Map.of()));
    }

    private ResponseEntity<ApiErrorResponse> validationError(HttpServletRequest request, String field) {
        return ResponseEntity.badRequest()
                .cacheControl(CacheControl.noStore())
                .body(ApiErrorResponse.of(
                        ApiErrorCode.VALIDATION_ERROR,
                        requestId(request),
                        Map.of("field", field)));
    }

    private MemberProfilePatch profilePatch(JsonNode body) {
        if (body == null || !body.isObject()) {
            throw new MemberProfileInvalidException("profile");
        }
        var allowed = Set.of("displayName", "characterId", "backgroundId");
        for (var field : body.propertyNames()) {
            if (!allowed.contains(field)) {
                throw new MemberProfileInvalidException(field);
            }
        }
        return new MemberProfilePatch(
                textField(body, "displayName"),
                textField(body, "characterId"),
                textField(body, "backgroundId"));
    }

    private String textField(JsonNode body, String field) {
        if (!body.has(field)) {
            return null;
        }
        var value = body.get(field);
        if (value == null || !value.isTextual()) {
            throw new MemberProfileInvalidException(field);
        }
        return value.textValue();
    }

    private String requestId(HttpServletRequest request) {
        var attribute = request.getAttribute(RequestIdFilter.ATTRIBUTE);
        if (attribute instanceof String requestId && !requestId.isBlank()) {
            return requestId;
        }
        var header = request.getHeader(RequestIdFilter.HEADER);
        return header == null || header.isBlank() ? "unknown" : header;
    }

    private ResponseCookie expireSessionCookie() {
        return ResponseCookie.from(SESSION_COOKIE, "")
                .path("/")
                .secure(true)
                .httpOnly(true)
                .sameSite("None")
                .maxAge(Duration.ZERO)
                .build();
    }

    record MemberMeResponse(
            String schemaVersion,
            String id,
            String displayName,
            String characterId,
            String backgroundId) {

        static MemberMeResponse from(MemberSummary member) {
            return new MemberMeResponse(
                    "1.2",
                    member.id().toString(),
                    member.displayName(),
                    member.characterId(),
                    member.backgroundId());
        }

        static MemberMeResponse from(MemberProfile profile) {
            return new MemberMeResponse(
                    "1.2",
                    profile.memberId().toString(),
                    profile.displayName(),
                    profile.characterId().name(),
                    profile.backgroundId().name());
        }
    }

    record MemberDeletingStatusResponse(String schemaVersion, String status) {
    }
}
