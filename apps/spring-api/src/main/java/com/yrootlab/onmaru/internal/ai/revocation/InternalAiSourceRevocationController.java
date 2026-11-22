package com.yrootlab.onmaru.internal.ai.revocation;

import com.yrootlab.onmaru.config.secrets.SecretProvider;
import com.yrootlab.onmaru.persistence.catalog.AiSourceRevocationPage;
import com.yrootlab.onmaru.persistence.catalog.JdbcAiSourceRevocationStore;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/internal/v1/ai/source-revocations")
public final class InternalAiSourceRevocationController {

    static final String SERVICE_SECRET_NAME = "internal-ai-service-token";
    private static final String BEARER_PREFIX = "Bearer ";

    private final JdbcAiSourceRevocationStore store;
    private final SecretProvider secrets;

    public InternalAiSourceRevocationController(JdbcAiSourceRevocationStore store, SecretProvider secrets) {
        this.store = store;
        this.secrets = secrets;
    }

    @GetMapping("/watermark")
    public ResponseEntity<?> watermark(@RequestHeader(name = "Authorization", required = false) String authorization) {
        if (!authenticated(authorization)) return unauthorized();
        try {
            return ok(new AiSourceRevocationWatermark(
                    JdbcAiSourceRevocationStore.CONTRACT_VERSION, store.watermark()));
        } catch (RuntimeException exception) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .cacheControl(CacheControl.noStore()).body(new FeedError("WATERMARK_UNAVAILABLE"));
        }
    }

    @GetMapping("/events")
    public ResponseEntity<?> events(
            @RequestHeader(name = "Authorization", required = false) String authorization,
            @RequestParam long afterSequence,
            @RequestParam(defaultValue = "100") int limit) {
        if (!authenticated(authorization)) return unauthorized();
        try {
            return ok(store.eventsAfter(afterSequence, limit));
        } catch (IllegalArgumentException exception) {
            return ResponseEntity.badRequest().cacheControl(CacheControl.noStore())
                    .body(new FeedError("CURSOR_OR_LIMIT_INVALID"));
        } catch (RuntimeException exception) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .cacheControl(CacheControl.noStore()).body(new FeedError("EVENT_FEED_UNAVAILABLE"));
        }
    }

    @PostMapping("/snapshots")
    public ResponseEntity<?> createSnapshot(
            @RequestHeader(name = "Authorization", required = false) String authorization) {
        if (!authenticated(authorization)) return unauthorized();
        try {
            var snapshot = store.beginSnapshot();
            String id = snapshot.snapshotId().toString();
            return ok(new AiSourceRevocationSnapshot(
                    JdbcAiSourceRevocationStore.CONTRACT_VERSION, id, snapshot.watermark(),
                    snapshot.activeRevisionId().toString(), snapshot.publicPlaceCount(), true));
        } catch (RuntimeException exception) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .cacheControl(CacheControl.noStore()).body(new FeedError("SNAPSHOT_UNAVAILABLE"));
        }
    }

    @GetMapping("/snapshots/{snapshotId}/public-places")
    public ResponseEntity<?> snapshotPublicPlaces(
            @RequestHeader(name = "Authorization", required = false) String authorization,
            @PathVariable UUID snapshotId,
            @RequestParam(required = false) String afterPublicId,
            @RequestParam(defaultValue = "100") int limit) {
        if (!authenticated(authorization)) return unauthorized();
        try {
            return ok(store.snapshotPublicPlaces(snapshotId, afterPublicId, limit));
        } catch (IllegalArgumentException exception) {
            return ResponseEntity.badRequest().cacheControl(CacheControl.noStore())
                    .body(new FeedError("SNAPSHOT_CURSOR_INVALID"));
        } catch (RuntimeException exception) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .cacheControl(CacheControl.noStore()).body(new FeedError("SNAPSHOT_UNAVAILABLE"));
        }
    }

    @GetMapping("/snapshots/{snapshotId}/events")
    public ResponseEntity<?> snapshotEvents(
            @RequestHeader(name = "Authorization", required = false) String authorization,
            @PathVariable UUID snapshotId,
            @RequestParam long afterSequence,
            @RequestParam(defaultValue = "100") int limit) {
        if (!authenticated(authorization)) return unauthorized();
        try {
            AiSourceRevocationPage page = store.snapshotEvents(snapshotId, afterSequence, limit);
            return ok(page);
        } catch (IllegalArgumentException exception) {
            return ResponseEntity.badRequest().cacheControl(CacheControl.noStore())
                    .body(new FeedError("SNAPSHOT_CURSOR_INVALID"));
        } catch (RuntimeException exception) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .cacheControl(CacheControl.noStore()).body(new FeedError("SNAPSHOT_UNAVAILABLE"));
        }
    }

    private boolean authenticated(String authorization) {
        if (authorization == null || !authorization.startsWith(BEARER_PREFIX)) return false;
        try {
            return secrets.get(SERVICE_SECRET_NAME).matches(authorization.substring(BEARER_PREFIX.length()));
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private static ResponseEntity<Object> ok(Object body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }

    private static ResponseEntity<Object> unauthorized() {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).cacheControl(CacheControl.noStore())
                .body(new FeedError("SERVICE_AUTH_INVALID"));
    }

    public record FeedError(String code) { }
}
