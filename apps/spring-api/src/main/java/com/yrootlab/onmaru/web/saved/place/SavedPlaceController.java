package com.yrootlab.onmaru.web.saved.place;

import com.yrootlab.onmaru.identity.lifecycle.MemberLifecycleService;
import com.yrootlab.onmaru.journey.saved.place.SavedPlaceLimitExceededException;
import com.yrootlab.onmaru.journey.saved.place.SavedPlaceNotFoundException;
import com.yrootlab.onmaru.journey.saved.place.SavedPlaceService;
import com.yrootlab.onmaru.web.common.error.ApiErrorResponse;
import com.yrootlab.onmaru.web.common.error.RequestIdFilter;
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

@RestController
public final class SavedPlaceController {

    private static final String SESSION_COOKIE = "__Host-onmaru-session";

    private final SavedPlaceService savedPlaceService;
    private final MemberLifecycleService memberLifecycleService;

    SavedPlaceController(SavedPlaceService savedPlaceService, MemberLifecycleService memberLifecycleService) {
        this.savedPlaceService = savedPlaceService;
        this.memberLifecycleService = memberLifecycleService;
    }

    @PutMapping("/api/v1/saved-resources/places/{placeId}")
    ResponseEntity<?> savePlace(
            @PathVariable String placeId,
            @CookieValue(name = SESSION_COOKIE, required = false) String sessionToken,
            HttpServletRequest request) {
        return memberLifecycleService.currentMember(sessionToken)
                .<ResponseEntity<?>>map(member -> saveForMember(member.id(), placeId, request))
                .orElseGet(() -> authRequired(request));
    }

    @DeleteMapping("/api/v1/saved-resources/places/{placeId}")
    ResponseEntity<?> deletePlace(
            @PathVariable String placeId,
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

    private String requestId(HttpServletRequest request) {
        var fromAttribute = request.getAttribute(RequestIdFilter.ATTRIBUTE);
        if (fromAttribute instanceof String requestId && !requestId.isBlank()) {
            return requestId;
        }
        var fromHeader = request.getHeader(RequestIdFilter.HEADER);
        return fromHeader == null || fromHeader.isBlank() ? UUID.randomUUID().toString() : fromHeader;
    }
}
