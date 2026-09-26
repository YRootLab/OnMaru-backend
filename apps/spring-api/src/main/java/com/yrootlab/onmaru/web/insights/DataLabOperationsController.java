package com.yrootlab.onmaru.web.insights;

import com.yrootlab.onmaru.insights.ingestion.DataLabVisitorIngestionService;
import com.yrootlab.onmaru.insights.ingestion.DataLabCollectionAlreadyRunningException;
import org.springframework.context.annotation.Profile;
import org.springframework.boot.info.BuildProperties;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.util.Objects;

@RestController
@Profile("production")
final class DataLabOperationsController {

    private final DataLabOperationsAuthenticator authenticator;
    private final DataLabVisitorIngestionService ingestionService;
    private final String buildGitSha;

    DataLabOperationsController(
            DataLabOperationsAuthenticator authenticator,
            DataLabVisitorIngestionService ingestionService,
            BuildProperties buildProperties) {
        this.authenticator = Objects.requireNonNull(authenticator);
        this.ingestionService = Objects.requireNonNull(ingestionService);
        this.buildGitSha = Objects.requireNonNullElse(buildProperties.get("gitSha"), "unknown");
    }

    @PostMapping("/api/v1/operations/datalab/visitor-sync")
    ResponseEntity<DataLabOperationsResponse> sync(
            @RequestHeader(name = "Authorization", required = false) String authorization) {
        if (!authenticator.authenticate(authorization)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        try {
            return ResponseEntity.ok(DataLabOperationsResponse.from(buildGitSha, ingestionService.sync()));
        } catch (DataLabCollectionAlreadyRunningException exception) {
            return ResponseEntity.status(HttpStatus.CONFLICT).build();
        }
    }
}
