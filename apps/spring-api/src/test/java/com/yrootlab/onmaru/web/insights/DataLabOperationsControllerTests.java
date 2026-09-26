package com.yrootlab.onmaru.web.insights;

import com.yrootlab.onmaru.config.secrets.SecretBundle;
import com.yrootlab.onmaru.config.secrets.SecretProvider;
import com.yrootlab.onmaru.insights.ingestion.DataLabCollectionExclusion;
import com.yrootlab.onmaru.insights.ingestion.DataLabCollectionReason;
import com.yrootlab.onmaru.insights.ingestion.DataLabVisitorFetchResult;
import com.yrootlab.onmaru.insights.ingestion.DataLabVisitorIngestionService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class DataLabOperationsControllerTests {

    private static final String CURRENT_TOKEN = "current-datalab-operations-token";
    private static final String PREVIOUS_TOKEN = "previous-datalab-operations-token";

    @Test
    void rejectsMissingAndWrongTokensWithoutStartingCollection() {
        var sourceCalls = new AtomicInteger();
        var controller = controller(() -> {
            sourceCalls.incrementAndGet();
            return skipped();
        });

        assertThat(controller.sync(null).getStatusCode().value()).isEqualTo(401);
        assertThat(controller.sync("Bearer wrong-token").getStatusCode().value()).isEqualTo(401);
        assertThat(sourceCalls).hasValue(0);
    }

    @Test
    void acceptsCurrentAndPreviousRotatingTokensWithSanitizedResult() {
        var controller = controller(DataLabOperationsControllerTests::skipped);

        var current = controller.sync("Bearer " + CURRENT_TOKEN);
        var previous = controller.sync("Bearer " + PREVIOUS_TOKEN);

        assertThat(current.getStatusCode().value()).isEqualTo(200);
        assertThat(previous.getStatusCode().value()).isEqualTo(200);
        assertThat(current.getBody()).isEqualTo(new DataLabOperationsResponse(
                false, 0, 2, 0,
                java.util.Map.of(
                        DataLabCollectionReason.PENDING_MAPPING, 1L,
                        DataLabCollectionReason.NO_ACTIVE_MAPPING, 1L)));
        assertThat(current.getBody().toString())
                .doesNotContain(CURRENT_TOKEN, PREVIOUS_TOKEN, "sourceUrl", "dataLabRegionCode");
    }

    @Test
    void rejectsConcurrentCollectionWithConflict() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var controller = controller(() -> {
            entered.countDown();
            try {
                assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(exception);
            }
            return skipped();
        });

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(() -> controller.sync("Bearer " + CURRENT_TOKEN));
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();

            assertThat(controller.sync("Bearer " + CURRENT_TOKEN).getStatusCode().value()).isEqualTo(409);

            release.countDown();
            assertThat(first.get(5, TimeUnit.SECONDS).getStatusCode().value()).isEqualTo(200);
        }
    }

    private DataLabOperationsController controller(
            com.yrootlab.onmaru.insights.ingestion.DataLabVisitorSource source) {
        SecretProvider secrets = name -> new SecretBundle(
                name, CURRENT_TOKEN, Optional.of(PREVIOUS_TOKEN));
        var service = new DataLabVisitorIngestionService(source, observations -> { });
        return new DataLabOperationsController(new DataLabOperationsAuthenticator(secrets), service);
    }

    private static DataLabVisitorFetchResult skipped() {
        return new DataLabVisitorFetchResult(List.of(), List.of(
                new DataLabCollectionExclusion("kr-48", DataLabCollectionReason.PENDING_MAPPING),
                new DataLabCollectionExclusion(null, DataLabCollectionReason.NO_ACTIVE_MAPPING)), false);
    }
}
