package com.yrootlab.onmaru.web.internal.mcp.quality;

import com.yrootlab.onmaru.catalog.application.query.quality.PublicSourceStateItem;
import com.yrootlab.onmaru.catalog.application.query.quality.PublicSourceStateQueryPort;
import com.yrootlab.onmaru.journey.observation.RunObservation;
import com.yrootlab.onmaru.journey.observation.RunObservationQueryPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Read-only Spring MCP Tool Adapter for Quality Harness (ADR-0018 / Issue #789).
 * Exposes:
 *  - quality_get_run_observation (requires scope: quality.read.run)
 *  - quality_get_public_source_state (requires scope: quality.read.source)
 */
@RestController
@RequestMapping("/internal/mcp/quality/tools")
public final class QualityMcpController {

    private static final Pattern ID_PATTERN = Pattern.compile("^[A-Za-z0-9_-]+$");
    private static final Pattern SOURCE_PATTERN = Pattern.compile("^[A-Za-z0-9._:-]+$");

    private final RunObservationQueryPort runObservationQueryPort;
    private final PublicSourceStateQueryPort publicSourceStateQueryPort;

    public QualityMcpController(
            RunObservationQueryPort runObservationQueryPort,
            PublicSourceStateQueryPort publicSourceStateQueryPort) {
        this.runObservationQueryPort = runObservationQueryPort;
        this.publicSourceStateQueryPort = publicSourceStateQueryPort;
    }

    @PostMapping("/quality_get_run_observation")
    public ResponseEntity<?> getRunObservation(
            @RequestHeader(name = "X-Scope", required = false) String scopeHeader,
            @RequestHeader(name = "Authorization", required = false) String authorization,
            @RequestBody(required = false) QualityRunObservationRequest request) {

        // Validate scope: quality.read.run required
        if (!hasRequiredScope(scopeHeader, authorization, "quality.read.run")) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(new QualityToolErrorResponse("SCOPE_DENIED", "quality.read.run scope required", false));
        }

        if (request == null || request.runId() == null || request.runId().isBlank()) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(new QualityToolErrorResponse("SCHEMA_MISMATCH", "runId is required", false));
        }

        String runIdStr = request.runId().trim();
        if (runIdStr.length() < 16 || runIdStr.length() > 128 || !ID_PATTERN.matcher(runIdStr).matches()) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(new QualityToolErrorResponse("SCHEMA_MISMATCH", "Invalid runId format", false));
        }

        UUID runUuid;
        try {
            runUuid = UUID.fromString(runIdStr);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(new QualityToolErrorResponse("NOT_FOUND_OR_HIDDEN", "Run observation not found", false));
        }

        return runObservationQueryPort.findByRunId(runUuid)
                .<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(new QualityToolErrorResponse("NOT_FOUND_OR_HIDDEN", "Run observation not found", false)));
    }

    @PostMapping("/quality_get_public_source_state")
    public ResponseEntity<?> getPublicSourceState(
            @RequestHeader(name = "X-Scope", required = false) String scopeHeader,
            @RequestHeader(name = "Authorization", required = false) String authorization,
            @RequestBody(required = false) QualityPublicSourceStateRequest request) {

        // Validate scope: quality.read.source required
        if (!hasRequiredScope(scopeHeader, authorization, "quality.read.source")) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(new QualityToolErrorResponse("SCOPE_DENIED", "quality.read.source scope required", false));
        }

        if (request == null || request.sourceId() == null || request.sourceRevision() == null) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(new QualityToolErrorResponse("SCHEMA_MISMATCH", "sourceId and sourceRevision are required", false));
        }

        String sourceId = request.sourceId().trim();
        String sourceRevision = request.sourceRevision().trim();

        if (sourceId.isEmpty() || sourceId.length() > 128 || !SOURCE_PATTERN.matcher(sourceId).matches()
                || sourceRevision.isEmpty() || sourceRevision.length() > 128 || !SOURCE_PATTERN.matcher(sourceRevision).matches()) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(new QualityToolErrorResponse("SCHEMA_MISMATCH", "Invalid sourceId or sourceRevision format", false));
        }

        return publicSourceStateQueryPort.findPublicSourceState(sourceId, sourceRevision)
                .<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(new QualityToolErrorResponse("NOT_FOUND_OR_HIDDEN", "Source state not found", false)));
    }

    private static boolean hasRequiredScope(String scopeHeader, String authorization, String requiredScope) {
        if (scopeHeader != null && scopeHeader.contains(requiredScope)) {
            return true;
        }
        if (authorization != null && authorization.contains(requiredScope)) {
            return true;
        }
        return false;
    }
}
