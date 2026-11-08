package com.yrootlab.onmaru.web.internal.mcp.journey;

import com.yrootlab.onmaru.catalog.application.query.journey.ApprovedPlaceLookupItem;
import com.yrootlab.onmaru.catalog.application.query.journey.PlaceQueryPort;
import com.yrootlab.onmaru.config.secrets.SecretProvider;
import com.yrootlab.onmaru.integration.ai.grant.QueryGrant;
import com.yrootlab.onmaru.integration.ai.grant.QueryGrantException;
import com.yrootlab.onmaru.integration.ai.grant.QueryGrantService;
import com.yrootlab.onmaru.journey.worker.plan.AiJourneyContracts.CanonicalField;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/internal/mcp/journey")
public final class JourneyQueryToolController {

    static final String SERVICE_SECRET_NAME = "internal-ai-service-token";
    private static final String BEARER_PREFIX = "Bearer ";

    private final PlaceQueryPort placeQueryPort;
    private final QueryGrantService grants;
    private final SecretProvider secrets;

    public JourneyQueryToolController(PlaceQueryPort placeQueryPort, QueryGrantService grants, SecretProvider secrets) {
        this.placeQueryPort = placeQueryPort;
        this.grants = grants;
        this.secrets = secrets;
    }

    @PostMapping("/tools/journey_execute_approved_lookup")
    public ResponseEntity<?> executeApprovedLookup(
            @RequestHeader(name = "Authorization", required = false) String authorization,
            @RequestBody JourneyQueryToolRequest request) {

        if (!serviceAuthenticated(authorization)) {
            return error(HttpStatus.UNAUTHORIZED, "SERVICE_AUTH_INVALID");
        }
        if (request == null || request.queryGrantId() == null) {
            return error(HttpStatus.UNAUTHORIZED, "GRANT_INVALID");
        }

        try {
            var scope = new QueryGrantService.LookupScope(request.regionCode(), request.category(), request.limit());
            List<ProjectedPlace> places = grants.lookup(request.queryGrantId(), scope,
                    grant -> project(grant, placeQueryPort.lookupApprovedPlaces(
                            request.regionCode(), request.category(), request.limit())));
            return ResponseEntity.ok(new JourneyQueryToolResponse(places));
        } catch (QueryGrantException exception) {
            return error(HttpStatus.valueOf(exception.code().status()), exception.code().name());
        }
    }

    private boolean serviceAuthenticated(String authorization) {
        if (authorization == null || !authorization.startsWith(BEARER_PREFIX)) {
            return false;
        }
        String candidate = authorization.substring(BEARER_PREFIX.length());
        try {
            return secrets.get(SERVICE_SECRET_NAME).matches(candidate);
        } catch (RuntimeException exception) {
            // Missing or unreadable secret material fails closed without echoing the credential or cause.
            return false;
        }
    }

    /** Applies the grant's place allow/deny lists and returns only the canonical fields it requested. */
    private static List<ProjectedPlace> project(QueryGrant grant, List<ApprovedPlaceLookupItem> items) {
        var allowed = grant.filters().placeIds();
        var excluded = grant.filters().excludedPlaceIds();
        var fields = grant.canonicalFields();
        return items.stream()
                .filter(item -> allowed == null || allowed.contains(item.placeId()))
                .filter(item -> excluded == null || !excluded.contains(item.placeId()))
                .limit(grant.maxPlaces())
                .map(item -> new ProjectedPlace(
                        item.placeId(),
                        fields.contains(CanonicalField.PUBLIC_ADDRESS) ? item.address() : null,
                        fields.contains(CanonicalField.REGION_CODE) ? item.regionCode() : null,
                        fields.contains(CanonicalField.CATEGORY_CODE) ? item.category() : null,
                        fields.contains(CanonicalField.PUBLIC_STATUS) ? "PUBLIC" : null))
                .toList();
    }

    private static ResponseEntity<Object> error(HttpStatus status, String code) {
        return ResponseEntity.status(status).body(new QueryToolError(code));
    }
}
