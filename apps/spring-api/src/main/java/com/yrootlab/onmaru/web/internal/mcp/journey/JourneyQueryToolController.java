package com.yrootlab.onmaru.web.internal.mcp.journey;

import com.yrootlab.onmaru.catalog.application.query.journey.PlaceQueryPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/mcp/journey")
public final class JourneyQueryToolController {

    private final PlaceQueryPort placeQueryPort;

    public JourneyQueryToolController(PlaceQueryPort placeQueryPort) {
        this.placeQueryPort = placeQueryPort;
    }

    @PostMapping("/tools/journey_execute_approved_lookup")
    public ResponseEntity<JourneyQueryToolResponse> executeApprovedLookup(
            @RequestHeader(name = "Authorization", required = false) String authorization,
            @RequestBody JourneyQueryToolRequest request) {

        if (request == null || request.queryGrantId() == null || request.queryGrantId().isBlank()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        // Validate opaque grant length: 32 ~ 128 chars
        if (request.queryGrantId().length() < 32 || request.queryGrantId().length() > 128) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        // Fixed limit: max 5
        int limit = request.limit() != null ? Math.min(Math.max(request.limit(), 1), 5) : 5;
        var places = placeQueryPort.lookupApprovedPlaces(request.regionCode(), request.category(), limit);

        return ResponseEntity.ok(new JourneyQueryToolResponse(places));
    }
}
