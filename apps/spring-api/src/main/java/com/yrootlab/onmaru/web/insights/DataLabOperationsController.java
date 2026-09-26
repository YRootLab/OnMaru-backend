package com.yrootlab.onmaru.web.insights;

import com.yrootlab.onmaru.insights.ingestion.DataLabVisitorIngestionService;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

@RestController
@Profile("production")
final class DataLabOperationsController {

    private final DataLabOperationsAuthenticator authenticator;
    private final DataLabVisitorIngestionService ingestionService;
    private final AtomicBoolean running = new AtomicBoolean();

    DataLabOperationsController(
            DataLabOperationsAuthenticator authenticator,
            DataLabVisitorIngestionService ingestionService) {
        this.authenticator = Objects.requireNonNull(authenticator);
        this.ingestionService = Objects.requireNonNull(ingestionService);
    }

    @PostMapping("/api/v1/operations/datalab/visitor-sync")
    ResponseEntity<DataLabOperationsResponse> sync(
            @RequestHeader(name = "Authorization", required = false) String authorization) {
        if (!authenticator.authenticate(authorization)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        if (!running.compareAndSet(false, true)) {
            return ResponseEntity.status(HttpStatus.CONFLICT).build();
        }
        try {
            return ResponseEntity.ok(DataLabOperationsResponse.from(ingestionService.sync()));
        } finally {
            running.set(false);
        }
    }
}
