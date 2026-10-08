package com.yrootlab.onmaru.scheduling.catalog.selected;

import com.yrootlab.onmaru.catalog.application.qualification.DiscoveryCandidatePolicy;
import com.yrootlab.onmaru.catalog.application.selectedsync.SelectedDiscoveryApprovalService;
import com.yrootlab.onmaru.catalog.application.selectedsync.SelectedDiscoverySync;
import com.yrootlab.onmaru.persistence.catalog.selected.JdbcSelectedDiscoveryStore;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.UUID;
import java.time.Instant;
import java.nio.charset.StandardCharsets;

/** Explicit one-shot operator workflow. Run the production jar with web-application-type=none. */
@Component
@Profile("production")
@ConditionalOnProperty(prefix = "onmaru.discovery.operator", name = "action")
public final class SelectedDiscoveryOperatorCommand implements ApplicationRunner {
    private final SelectedDiscoveryApprovalService approvals;
    private final JdbcSelectedDiscoveryStore store;
    private final SelectedDiscoverySync sync;
    private final Environment environment;

    public SelectedDiscoveryOperatorCommand(SelectedDiscoveryApprovalService approvals,
                                            JdbcSelectedDiscoveryStore store, SelectedDiscoverySync sync,
                                            Environment environment) {
        this.approvals = approvals; this.store = store; this.sync = sync; this.environment = environment;
    }

    @Override public void run(ApplicationArguments args) {
        if (!"none".equals(environment.getProperty("spring.main.web-application-type")))
            throw new IllegalStateException("Discovery operator command requires non-web process");
        String action = required("onmaru.discovery.operator.action");
        switch (action) {
            case "run" -> {
                Instant due = Instant.parse(required("onmaru.discovery.operator.due-at"));
                UUID runId = UUID.nameUUIDFromBytes(("selected-discovery:" + due).getBytes(StandardCharsets.UTF_8));
                System.out.println("runId=" + runId + " result=" + sync.run(runId, due));
            }
            case "status" -> System.out.println("activeDiscoveryRevision=" + store.activeRevisionId());
            case "preview" -> {
                var preview = approvals.preview(required("onmaru.discovery.operator.content-id"));
                System.out.println("contentId=" + preview.contentId());
                System.out.println("title=" + preview.row().field("title"));
                System.out.println("overview=" + preview.row().field("overview"));
                System.out.println("automaticDecision=" + preview.automaticDecision().status() + "/" + preview.automaticDecision().reasonCode());
                System.out.println("listHash=" + preview.listHash());
                System.out.println("detailHash=" + preview.detailHash());
                System.out.println("fingerprintDigest=" + preview.fingerprintDigest());
            }
            case "approve" -> {
                approvals.approve(required("onmaru.discovery.operator.content-id"),
                        DiscoveryCandidatePolicy.Role.valueOf(required("onmaru.discovery.operator.role")),
                        required("onmaru.discovery.operator.list-hash"),
                        required("onmaru.discovery.operator.detail-hash"),
                        required("onmaru.discovery.operator.fingerprint-digest"),
                        required("onmaru.discovery.operator.evidence-ref"),
                        required("onmaru.discovery.operator.reviewer"),
                        Boolean.parseBoolean(required("onmaru.discovery.operator.detail-reviewed")),
                        Boolean.parseBoolean(required("onmaru.discovery.operator.rights-reviewed")));
                System.out.println("approval=saved");
            }
            case "revoke" -> {
                approvals.revoke(required("onmaru.discovery.operator.content-id"),
                        required("onmaru.discovery.operator.reviewer"));
                System.out.println("approval=revoked; active revision unchanged");
            }
            case "rollback" -> {
                boolean done = store.rollback(UUID.fromString(required("onmaru.discovery.operator.expected-active")),
                        UUID.fromString(required("onmaru.discovery.operator.target-revision")));
                if (!done) throw new IllegalStateException("Rollback target or expected active revision mismatch");
                System.out.println("discoveryRevision=rolled-back");
            }
            default -> throw new IllegalArgumentException("Unknown discovery operator action: " + action);
        }
    }

    private String required(String property) {
        String value = environment.getProperty(property);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Missing " + property);
        return value;
    }
}
