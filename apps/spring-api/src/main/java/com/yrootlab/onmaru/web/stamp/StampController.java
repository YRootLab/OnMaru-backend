package com.yrootlab.onmaru.web.stamp;

import com.yrootlab.onmaru.identity.lifecycle.MemberLifecycleService;
import com.yrootlab.onmaru.security.web.PrivateResponse;
import com.yrootlab.onmaru.stamp.CheckInCommand;
import com.yrootlab.onmaru.stamp.CheckInInputInvalidException;
import com.yrootlab.onmaru.stamp.StampAwardSummary;
import com.yrootlab.onmaru.stamp.StampBook;
import com.yrootlab.onmaru.stamp.StampBookItem;
import com.yrootlab.onmaru.stamp.StampBookSummary;
import com.yrootlab.onmaru.stamp.StampCheckIn;
import com.yrootlab.onmaru.stamp.StampCheckInResult;
import com.yrootlab.onmaru.stamp.StampDefinition;
import com.yrootlab.onmaru.stamp.StampService;
import com.yrootlab.onmaru.web.common.error.ApiErrorResponse;
import com.yrootlab.onmaru.web.common.error.RequestIdFilter;
import com.yrootlab.onmaru.web.common.idempotency.IdempotencyCommand;
import com.yrootlab.onmaru.web.common.idempotency.IdempotencyFingerprint;
import com.yrootlab.onmaru.web.common.idempotency.IdempotencyKey;
import com.yrootlab.onmaru.web.common.idempotency.IdempotencyService;
import com.yrootlab.onmaru.web.common.idempotency.IdempotentResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Tag(name = "수결첩", description = "한옥 방문 수결 카탈로그, 개인 수결첩, 위치 체크인 API")
@RestController
public final class StampController {

    private static final String SESSION_COOKIE = "__Host-onmaru-session";
    private static final String SCHEMA_VERSION = "1.2";

    private final StampService stamps;
    private final MemberLifecycleService members;
    private final IdempotencyService idempotency;

    StampController(StampService stamps, MemberLifecycleService members, IdempotencyService idempotency) {
        this.stamps = stamps;
        this.members = members;
        this.idempotency = idempotency;
    }

    @Operation(summary = "수결 카탈로그 조회")
    @GetMapping("/api/v1/stamps")
    ResponseEntity<StampCatalogResponse> catalog() {
        var body = new StampCatalogResponse(
                SCHEMA_VERSION, stamps.definitions().stream().map(StampDefinitionResponse::from).toList());
        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(Duration.ofMinutes(5)).cachePublic())
                .body(body);
    }

    @Operation(summary = "내 수결첩 조회")
    @PrivateResponse
    @GetMapping("/api/v1/me/stamp-book")
    ResponseEntity<?> book(
            @CookieValue(name = SESSION_COOKIE, required = false) String sessionToken,
            HttpServletRequest request) {
        return members.currentMember(sessionToken)
                .<ResponseEntity<?>>map(member -> ResponseEntity.ok()
                        .cacheControl(CacheControl.noStore())
                        .body(StampBookResponse.from(stamps.book(member.id()))))
                .orElseGet(() -> authRequired(request));
    }

    @Operation(summary = "현재 위치로 한옥 방문 체크인")
    @PrivateResponse
    @PostMapping("/api/v1/places/{placeId}/check-ins")
    ResponseEntity<?> checkIn(
            @PathVariable String placeId,
            @RequestBody(required = false) CheckInRequest body,
            @RequestHeader(name = IdempotencyKey.HEADER, required = false) String idempotencyKey,
            @CookieValue(name = SESSION_COOKIE, required = false) String sessionToken,
            HttpServletRequest request) {
        return members.currentMember(sessionToken)
                .<ResponseEntity<?>>map(member -> checkInForMember(
                        member.id(), placeId, body, idempotencyKey, request))
                .orElseGet(() -> authRequired(request));
    }

    private ResponseEntity<?> checkInForMember(
            UUID memberId,
            String placeId,
            CheckInRequest body,
            String idempotencyKey,
            HttpServletRequest request) {
        var command = command(placeId, body);
        var path = request.getRequestURI();
        var response = idempotency.execute(new IdempotencyCommand(
                IdempotencyKey.fromHeader(idempotencyKey).value(),
                memberId.toString(),
                request.getMethod(),
                path,
                IdempotencyFingerprint.sha256(request.getMethod(), path, memberId.toString(), command)), () -> {
            var result = stamps.checkIn(memberId, command);
            var responseBody = CheckInResponse.from(result);
            if (result.checkIn().alreadyCheckedIn()) {
                return IdempotentResponse.ok(responseBody);
            }
            return IdempotentResponse.created("/api/v1/check-ins/" + result.checkIn().id(), responseBody);
        });
        return toResponse(response);
    }

    private CheckInCommand command(String placeId, CheckInRequest body) {
        if (body == null) {
            throw new CheckInInputInvalidException("body");
        }
        if (body.latitude() == null) {
            throw new CheckInInputInvalidException("latitude");
        }
        if (body.longitude() == null) {
            throw new CheckInInputInvalidException("longitude");
        }
        if (body.accuracyMeters() == null) {
            throw new CheckInInputInvalidException("accuracyMeters");
        }
        return new CheckInCommand(placeId, body.latitude(), body.longitude(), body.accuracyMeters());
    }

    private ResponseEntity<?> toResponse(IdempotentResponse response) {
        var builder = ResponseEntity.status(response.status()).cacheControl(CacheControl.noStore());
        response.headers().forEach(builder::header);
        return builder.body(response.body());
    }

    private ResponseEntity<ApiErrorResponse> authRequired(HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .cacheControl(CacheControl.noStore())
                .body(new ApiErrorResponse(
                        SCHEMA_VERSION, "AUTH_REQUIRED", "Authentication is required.",
                        requestId(request), Map.of()));
    }

    private String requestId(HttpServletRequest request) {
        var value = request.getAttribute(RequestIdFilter.ATTRIBUTE);
        return value instanceof String requestId && !requestId.isBlank()
                ? requestId
                : UUID.randomUUID().toString();
    }

    record CheckInRequest(Double latitude, Double longitude, Double accuracyMeters) {
    }

    record StampCatalogResponse(String schemaVersion, List<StampDefinitionResponse> stamps) {
    }

    record StampDefinitionResponse(
            String code, String name, String description, String conditionLabel, String sealText,
            String iconName, String color, String rarity, String conditionType,
            Integer requiredCount, String regionGroup, int sortOrder) {
        static StampDefinitionResponse from(StampDefinition value) {
            return new StampDefinitionResponse(
                    value.code(), value.name(), value.description(), value.conditionLabel(), value.sealText(),
                    value.iconName(), value.color(), value.rarity().name(), value.conditionType().name(),
                    value.requiredCount(), value.regionGroup(), value.sortOrder());
        }
    }

    record StampBookResponse(String schemaVersion, StampBookSummary summary, List<StampBookItem> stamps) {
        static StampBookResponse from(StampBook value) {
            return new StampBookResponse(SCHEMA_VERSION, value.summary(), value.items());
        }
    }

    record CheckInResponse(
            String schemaVersion, StampCheckIn checkIn, List<StampAwardSummary> newAwards,
            StampBookSummary summary) {
        static CheckInResponse from(StampCheckInResult value) {
            return new CheckInResponse(
                    SCHEMA_VERSION, value.checkIn(), value.newAwards(), value.summary());
        }
    }
}
