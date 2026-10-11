package com.yrootlab.onmaru.tourism.catalog;

import com.yrootlab.onmaru.catalog.application.qualification.SourceQualificationPolicy;
import com.yrootlab.onmaru.persistence.catalog.JdbcTourApiCatalogPublisher;
import com.yrootlab.onmaru.tourism.catalog.sync.TourApiCatalogSnapshotSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicInteger;

/** Read-only source preflight; it never starts a catalog publication session. */
@Component
@Profile("production")
@ConditionalOnProperty(prefix = "onmaru.tourapi", name = "selected-preview", havingValue = "true")
public final class SelectedCatalogPreviewCommand implements ApplicationRunner {
    private final TourApiCatalogSnapshotSource source;
    private final JdbcTourApiCatalogPublisher publisher;
    private final String webApplicationType;

    public SelectedCatalogPreviewCommand(TourApiCatalogSnapshotSource source,
            JdbcTourApiCatalogPublisher publisher,
            @Value("${spring.main.web-application-type:servlet}") String webApplicationType) {
        this.source = source;
        this.publisher = publisher;
        this.webApplicationType = webApplicationType;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!"none".equals(webApplicationType))
            throw new IllegalStateException("Selected catalog preview requires a non-web process");
        var policy = SourceQualificationPolicy.withDefaultAllowlist();
        var qualified = new AtomicInteger();
        var skipped = new AtomicInteger();
        var quarantined = new AtomicInteger();
        Map<String, Integer> categories = new TreeMap<>();
        var requiredVerified = publisher.verifiedPublicPlaceSources();
        var snapshot = source.streamSelectedSnapshot(rows -> rows.forEach(row -> {
            var result = policy.qualify(row);
            switch (result.status()) {
                case CANDIDATE -> {
                    qualified.incrementAndGet();
                    categories.merge(result.candidate().orElseThrow().category().name(), 1, Integer::sum);
                }
                case SKIPPED -> skipped.incrementAndGet();
                case QUARANTINED -> quarantined.incrementAndGet();
            }
        }), requiredVerified);
        System.out.println("selectedCatalogPreview raw=" + snapshot.rawCount()
                + " qualified=" + qualified.get() + " skipped=" + skipped.get()
                + " sourceQuarantined=" + snapshot.sourceQuarantineCount()
                + " qualificationQuarantined=" + quarantined.get()
                + " verifiedCarryovers=" + requiredVerified.size() + " categories=" + categories);
    }
}
