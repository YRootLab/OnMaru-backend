package com.yrootlab.onmaru.web.internal.kcontents;

import com.yrootlab.onmaru.admin.auth.AdminAuthenticationException;
import com.yrootlab.onmaru.admin.auth.AdminAuthenticator;
import com.yrootlab.onmaru.admin.auth.AdminPrincipal;
import com.yrootlab.onmaru.persistence.kcontents.JdbcExtractionValidationService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Human review is an explicit admin action; worker credentials cannot approve facts. */
@RestController
@ConditionalOnProperty(name="onmaru.kcontents.research.enabled",havingValue="true")
@RequestMapping("/api/v1/internal/kcontents/research/admin/validation")
public final class ExtractionReviewController {
    private final JdbcExtractionValidationService validation;
    private final AdminAuthenticator admins;

    public ExtractionReviewController(JdbcExtractionValidationService validation, AdminAuthenticator admins) {
        this.validation = validation; this.admins = admins;
    }

    @GetMapping("/reviews")
    public ResponseEntity<?> reviews(@RequestHeader(name="Authorization",required=false) String authorization,
                                     @RequestParam(defaultValue="50") int limit) {
        if (principal(authorization) == null) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        try { return ResponseEntity.ok(validation.openReviews(limit)); }
        catch (IllegalArgumentException failure) { return ResponseEntity.badRequest().body(Map.of("code",failure.getMessage())); }
    }

    public record Decision(boolean approve, List<UUID> confirmedEvidenceIds) { }
    @PostMapping("/reviews/{reviewId}/decision")
    public ResponseEntity<?> decide(@PathVariable UUID reviewId,
                                    @RequestHeader(name="Authorization",required=false) String authorization,
                                    @RequestBody Decision decision) {
        AdminPrincipal actor = principal(authorization);
        if (actor == null) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        try {
            validation.decide(reviewId,actor.email(),decision.approve(),decision.confirmedEvidenceIds());
            return ResponseEntity.ok(Map.of("status",decision.approve()?"APPROVED":"REJECTED"));
        } catch (IllegalArgumentException failure) {
            return ResponseEntity.badRequest().body(Map.of("code",failure.getMessage()));
        } catch (IllegalStateException failure) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("code",failure.getMessage()));
        }
    }

    private AdminPrincipal principal(String authorization) {
        try { return admins.authenticate(authorization); }
        catch (AdminAuthenticationException failure) { return null; }
    }
}
