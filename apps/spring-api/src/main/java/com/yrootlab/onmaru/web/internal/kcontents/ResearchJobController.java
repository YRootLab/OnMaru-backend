package com.yrootlab.onmaru.web.internal.kcontents;

import com.yrootlab.onmaru.admin.auth.AdminAuthenticator;
import com.yrootlab.onmaru.admin.auth.AdminAuthenticationException;
import com.yrootlab.onmaru.kcontents.research.ResearchSubmission;
import com.yrootlab.onmaru.persistence.kcontents.JdbcResearchJobStore;
import com.yrootlab.onmaru.persistence.kcontents.JdbcResearchJobStore.Evidence;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Pull-only worker boundary. The configured token cannot call admin operations. */
@RestController
@ConditionalOnProperty(name="onmaru.kcontents.research.enabled",havingValue="true")
@RequestMapping("/api/v1/internal/kcontents/research")
public final class ResearchJobController {
    private final JdbcResearchJobStore jobs;
    private final AdminAuthenticator admins;
    private final String workerToken;

    public ResearchJobController(JdbcResearchJobStore jobs, AdminAuthenticator admins,
                                 @Value("${onmaru.kcontents.research.worker-token:}") String workerToken) {
        this.jobs=jobs;this.admins=admins;this.workerToken=workerToken;
    }

    @PostMapping("/jobs/lease")
    public ResponseEntity<?> lease(@RequestHeader(name="Authorization",required=false) String authorization,
                                   @RequestHeader(name="X-Worker-Id",required=false) String worker) {
        if(!workerAuthorized(authorization)) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        try {var lease=jobs.lease(worker);return lease==null?ResponseEntity.noContent().build():ResponseEntity.ok(lease);}
        catch(IllegalArgumentException exception){return bad(exception);}
    }

    @PostMapping("/jobs/{jobId}/evidence")
    public ResponseEntity<?> evidence(@PathVariable UUID jobId,@RequestHeader(name="Authorization",required=false) String authorization,
                                      @RequestHeader(name="X-Worker-Id",required=false) String worker,
                                      @RequestHeader(name="X-Lease-Token",required=false) String leaseToken,
                                      @RequestBody List<Evidence> bundle) {
        if(!workerAuthorized(authorization)) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        try{return ResponseEntity.ok(Map.of("evidenceIds",jobs.addEvidence(jobId,worker,leaseToken,bundle)));}
        catch(IllegalArgumentException exception){return bad(exception);}
        catch(IllegalStateException exception){return conflict(exception);}
    }

    @PostMapping("/jobs/{jobId}/submit")
    public ResponseEntity<?> submit(@PathVariable UUID jobId,@RequestHeader(name="Authorization",required=false) String authorization,
                                    @RequestHeader(name="X-Worker-Id",required=false) String worker,
                                    @RequestHeader(name="X-Lease-Token",required=false) String leaseToken,
                                    @RequestHeader(name="Idempotency-Key",required=false) String key,
                                    @RequestBody ResearchSubmission submission) {
        if(!workerAuthorized(authorization)) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        try{return ResponseEntity.ok(Map.of("status",jobs.submit(jobId,worker,leaseToken,key,submission)));}
        catch(IllegalArgumentException exception){return bad(exception);}
        catch(IllegalStateException exception){return conflict(exception);}
    }

    public record FailureRequest(String code) { }
    @PostMapping("/jobs/{jobId}/fail")
    public ResponseEntity<?> fail(@PathVariable UUID jobId,@RequestHeader(name="Authorization",required=false) String authorization,
                                  @RequestHeader(name="X-Worker-Id",required=false) String worker,
                                  @RequestHeader(name="X-Lease-Token",required=false) String leaseToken,
                                  @RequestHeader(name="Idempotency-Key",required=false) String key,
                                  @RequestBody FailureRequest failure) {
        if(!workerAuthorized(authorization)) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        try{return ResponseEntity.ok(Map.of("status",jobs.fail(jobId,worker,leaseToken,key,failure.code())));}
        catch(IllegalArgumentException exception){return bad(exception);}
        catch(IllegalStateException exception){return conflict(exception);}
    }

    public record EnqueueRequest(UUID placeId,String reason,String sourceFingerprint,String inputJson) { }
    @PostMapping("/admin/jobs")
    public ResponseEntity<?> enqueue(@RequestHeader(name="Authorization",required=false) String authorization,
                                     @RequestBody EnqueueRequest input) {
        if(!adminAuthorized(authorization)) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        try{return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("jobId",jobs.enqueue(input.placeId(),input.reason(),input.sourceFingerprint(),input.inputJson())));}
        catch(IllegalArgumentException exception){return bad(exception);}
    }

    @GetMapping("/admin/jobs/{jobId}")
    public ResponseEntity<?> status(@PathVariable UUID jobId,@RequestHeader(name="Authorization",required=false) String authorization) {
        if(!adminAuthorized(authorization)) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        try{return ResponseEntity.ok(jobs.status(jobId));}
        catch(IllegalArgumentException exception){return ResponseEntity.notFound().build();}
    }

    @PostMapping("/admin/jobs/{jobId}/requeue")
    public ResponseEntity<?> requeue(@PathVariable UUID jobId,@RequestHeader(name="Authorization",required=false) String authorization) {
        if(!adminAuthorized(authorization)) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        try {jobs.requeue(jobId,"admin");return ResponseEntity.ok(Map.of("status","QUEUED"));}
        catch(IllegalStateException exception){return conflict(exception);}
    }

    private boolean workerAuthorized(String authorization) {
        if(workerToken.isBlank() || authorization==null || !authorization.startsWith("Bearer ")) return false;
        return MessageDigest.isEqual(workerToken.getBytes(StandardCharsets.UTF_8),authorization.substring(7).getBytes(StandardCharsets.UTF_8));
    }
    private boolean adminAuthorized(String authorization) {
        try {admins.authenticate(authorization);return true;} catch(AdminAuthenticationException exception){return false;}
    }
    private static ResponseEntity<?> bad(Exception exception){return ResponseEntity.badRequest().body(Map.of("code",exception.getMessage()));}
    private static ResponseEntity<?> conflict(Exception exception){return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("code",exception.getMessage()));}
}
