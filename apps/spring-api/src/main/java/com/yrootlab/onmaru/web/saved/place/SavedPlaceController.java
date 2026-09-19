package com.yrootlab.onmaru.web.saved.place;

import com.yrootlab.onmaru.catalog.application.query.detail.PlaceDetailUnavailableException;
import com.yrootlab.onmaru.identity.lifecycle.MemberLifecycleService;
import com.yrootlab.onmaru.journey.saved.place.SavedPlaceLimitExceededException;
import com.yrootlab.onmaru.journey.saved.place.SavedPlaceNotFoundException;
import com.yrootlab.onmaru.journey.saved.place.SavedPlaceService;
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
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

@Tag(name = "05. 개인화 & 타임라인 (Saved & Timeline)", description = "북마크/저장한 장소 및 오디오 도슨트, 내 활동 타임라인 및 회원 프로필 API")
@RestController
public final class SavedPlaceController {

    private static final String SESSION_COOKIE = "__Host-onmaru-session";

    private final SavedPlaceService savedPlaceService;
    private final MemberLifecycleService memberLifecycleService;

    SavedPlaceController(SavedPlaceService savedPlaceService, MemberLifecycleService memberLifecycleService) {
        this.savedPlaceService = savedPlaceService;
        this.memberLifecycleService = memberLifecycleService;
    }

    @Operation(
            summary = "장소/한옥 북마크 및 찜하기 저장",
            description = "특정 장소 또는 한옥을 내 보관함에 찜하기(저장) 처리합니다."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "장소 저장 성공"),
            @ApiResponse(responseCode = "401", description = "로그인 세션 필요", content = @Content(schema = @Schema(implementation = ApiErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "장소를 찾을 수 없음", content = @Content(schema = @Schema(implementation = ApiErrorResponse.class))),
            @ApiResponse(responseCode = "409", description = "최대 저장 한도 초과", content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    })
    @PutMapping("/api/v1/saved-resources/places/{placeId}")
    ResponseEntity<?> savePlace(
            @Parameter(description = "장소 고유 식별자", example = "place-seoul-bukchon-001")
            @PathVariable String placeId,
            @Parameter(description = "회원 세션 쿠키", hidden = true)
            @CookieValue(name = SESSION_COOKIE, required = false) String sessionToken,
            HttpServletRequest request) {
        return memberLifecycleService.currentMember(sessionToken)
                .<ResponseEntity<?>>map(member -> saveForMember(member.id(), placeId, request))
                .orElseGet(() -> authRequired(request));
    }

    @Operation(
            summary = "장소/한옥 북마크 저장 취소",
            description = "내 보관함에서 저장된 장소를 삭제합니다."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "저장 취소 완료"),
            @ApiResponse(responseCode = "401", description = "로그인 세션 필요", content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    })
    @DeleteMapping("/api/v1/saved-resources/places/{placeId}")
    ResponseEntity<?> deletePlace(
            @Parameter(description = "장소 고유 식별자", example = "place-seoul-bukchon-001")
            @PathVariable String placeId,
            @Parameter(description = "회원 세션 쿠키", hidden = true)
            @CookieValue(name = SESSION_COOKIE, required = false) String sessionToken,
            HttpServletRequest request) {
        return memberLifecycleService.currentMember(sessionToken)
                .<ResponseEntity<?>>map(member -> {
                    savedPlaceService.delete(member.id(), placeId);
                    return ResponseEntity.noContent()
                            .cacheControl(CacheControl.noStore())
                            .build();
                })
                .orElseGet(() -> authRequired(request));
    }

    private ResponseEntity<?> saveForMember(UUID memberId, String placeId, HttpServletRequest request) {
        try {
            return ResponseEntity.ok()
                    .cacheControl(CacheControl.noStore())
                    .body(savedPlaceService.save(memberId, placeId));
        } catch (SavedPlaceNotFoundException exception) {
            return notFound(request);
        } catch (SavedPlaceLimitExceededException exception) {
            return saveLimit(request, exception.limit());
        } catch (PlaceDetailUnavailableException exception) {
            return serviceUnavailable(request);
        }
    }

    private ResponseEntity<ApiErrorResponse> authRequired(HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .cacheControl(CacheControl.noStore())
                .body(new ApiErrorResponse(
                        "1.2",
                        "AUTH_REQUIRED",
                        "Authentication is required to save this place.",
                        requestId(request),
                        Map.of()));
    }

    private ResponseEntity<ApiErrorResponse> notFound(HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .cacheControl(CacheControl.noStore())
                .body(new ApiErrorResponse(
                        "1.2",
                        "NOT_FOUND",
                        "The place is not available.",
                        requestId(request),
                        Map.of()));
    }

    private ResponseEntity<ApiErrorResponse> saveLimit(HttpServletRequest request, int limit) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .cacheControl(CacheControl.noStore())
                .body(new ApiErrorResponse(
                        "1.2",
                        "SAVE_LIMIT",
                        "Saved resource limit exceeded.",
                        requestId(request),
                        Map.of("limit", limit)));
    }

    private ResponseEntity<ApiErrorResponse> serviceUnavailable(HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .cacheControl(CacheControl.noStore())
                .body(new ApiErrorResponse(
                        "1.2",
                        "SERVICE_UNAVAILABLE",
                        "Current place data is unavailable.",
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
