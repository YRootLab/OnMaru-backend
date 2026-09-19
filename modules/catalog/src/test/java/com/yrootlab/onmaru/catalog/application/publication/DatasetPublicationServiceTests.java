package com.yrootlab.onmaru.catalog.application.publication;

import com.yrootlab.onmaru.catalog.application.sync.SyncRunLease;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class DatasetPublicationServiceTests {

    private static final Instant NOW = Instant.parse("2026-09-15T03:20:00Z");
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

    @Test
    void lastPageFailureLeavesActiveRevisionAndWatermarkUnchanged() {
        UUID baseRevision = UUID.randomUUID();
        UUID stagedRevision = UUID.randomUUID();
        var store = new InMemoryPublicationStore("kto-korean-tour", baseRevision);
        store.stage(stagedRevision, StageValidation.incomplete("LAST_PAGE_NOT_SEEN"));
        store.watermark = new SourceWatermark("20260914030000", "126508", Instant.parse("2026-09-14T03:20:00Z"));
        var service = new DatasetPublicationService(store, clock);

        PublicationResult result = service.publish(new PublicationCommand(
                lease(1),
                stagedRevision,
                baseRevision,
                PublicationMode.FULL,
                new SourceWatermark("20260915030000", "200000", NOW),
                0
        ));

        assertThat(result.status()).isEqualTo(PublicationStatus.STAGE_INCOMPLETE);
        assertThat(store.activeRevision).isEqualTo(baseRevision);
        assertThat(store.watermark).isEqualTo(new SourceWatermark(
                "20260914030000",
                "126508",
                Instant.parse("2026-09-14T03:20:00Z")
        ));
        assertThat(store.publishAttempts).isEmpty();
    }

    @Test
    void publishesReadyRevisionWithWatermarkAndTombstoneCountWhenLeaseStillHeld() {
        UUID baseRevision = UUID.randomUUID();
        UUID stagedRevision = UUID.randomUUID();
        var store = new InMemoryPublicationStore("kto-korean-tour", baseRevision);
        store.stage(stagedRevision, StageValidation.ready(124, false));
        var service = new DatasetPublicationService(store, clock);
        var watermark = new SourceWatermark("20260915030000", "200000", NOW);

        PublicationResult result = service.publish(new PublicationCommand(
                lease(1),
                stagedRevision,
                baseRevision,
                PublicationMode.DELTA,
                watermark,
                7
        ));

        assertThat(result.status()).isEqualTo(PublicationStatus.PUBLISHED);
        assertThat(store.activeRevision).isEqualTo(stagedRevision);
        assertThat(store.watermark).isEqualTo(watermark);
        assertThat(store.publishedAt).isEqualTo(NOW);
        assertThat(store.tombstoneCount).isEqualTo(7);
    }

    @Test
    void emptyFullSyncRequiresExplicitValidationAndDoesNotPublishAsMassDeletion() {
        UUID baseRevision = UUID.randomUUID();
        UUID emptyRevision = UUID.randomUUID();
        var store = new InMemoryPublicationStore("kto-korean-tour", baseRevision);
        store.stage(emptyRevision, StageValidation.ready(0, false));
        var service = new DatasetPublicationService(store, clock);

        PublicationResult result = service.publish(new PublicationCommand(
                lease(1),
                emptyRevision,
                baseRevision,
                PublicationMode.FULL,
                new SourceWatermark("20260915030000", "0", NOW),
                120
        ));

        assertThat(result.status()).isEqualTo(PublicationStatus.EMPTY_FULL_SYNC_REQUIRES_REVIEW);
        assertThat(store.activeRevision).isEqualTo(baseRevision);
        assertThat(store.watermark).isNull();
        assertThat(store.tombstoneCount).isZero();
    }

    @Test
    void concurrentPublishAndStaleLeaseWriterCannotChangePublishedResult() {
        UUID baseRevision = UUID.randomUUID();
        UUID firstRevision = UUID.randomUUID();
        UUID secondRevision = UUID.randomUUID();
        var store = new InMemoryPublicationStore("kto-korean-tour", baseRevision);
        store.stage(firstRevision, StageValidation.ready(10, false));
        store.stage(secondRevision, StageValidation.ready(11, false));
        var service = new DatasetPublicationService(store, clock);

        PublicationResult first = service.publish(new PublicationCommand(
                lease(1),
                firstRevision,
                baseRevision,
                PublicationMode.DELTA,
                new SourceWatermark("20260915030100", "10", NOW),
                1
        ));
        store.generation = 2;
        PublicationResult stale = service.publish(new PublicationCommand(
                lease(1),
                secondRevision,
                firstRevision,
                PublicationMode.DELTA,
                new SourceWatermark("20260915030200", "11", NOW),
                2
        ));
        PublicationResult staleBase = service.publish(new PublicationCommand(
                lease(2),
                secondRevision,
                baseRevision,
                PublicationMode.DELTA,
                new SourceWatermark("20260915030200", "11", NOW),
                2
        ));

        assertThat(first.status()).isEqualTo(PublicationStatus.PUBLISHED);
        assertThat(stale.status()).isEqualTo(PublicationStatus.LEASE_LOST);
        assertThat(staleBase.status()).isEqualTo(PublicationStatus.ACTIVE_REVISION_CHANGED);
        assertThat(store.activeRevision).isEqualTo(firstRevision);
        assertThat(store.watermark).isEqualTo(new SourceWatermark("20260915030100", "10", NOW));
        assertThat(store.tombstoneCount).isEqualTo(1);
    }

    private SyncRunLease lease(int generation) {
        return new SyncRunLease(UUID.randomUUID(), "kto-korean-tour", "worker-a", generation);
    }

    private static final class InMemoryPublicationStore implements PublicationStore {

        private final String dataset;
        private final List<PublicationAttempt> publishAttempts = new ArrayList<>();
        private final List<StagedRevision> stagedRevisions = new ArrayList<>();
        private UUID activeRevision;
        private SourceWatermark watermark;
        private Instant publishedAt;
        private long tombstoneCount;
        private int generation = 1;

        private InMemoryPublicationStore(String dataset, UUID activeRevision) {
            this.dataset = dataset;
            this.activeRevision = activeRevision;
        }

        @Override
        public Optional<StageValidation> stageValidation(UUID revisionId) {
            return stagedRevisions.stream()
                    .filter(revision -> revision.revisionId().equals(revisionId))
                    .findFirst()
                    .map(StagedRevision::validation);
        }

        @Override
        public PublicationStatus publishIfLeaseAndBaseRevisionMatch(
                SyncRunLease lease,
                PublicationPlan plan,
                Instant publishedAt
        ) {
            publishAttempts.add(new PublicationAttempt(lease, plan));
            if (lease.generation() != generation || !lease.ownerToken().equals("worker-a")) {
                return PublicationStatus.LEASE_LOST;
            }
            if (!dataset.equals(lease.dataset()) || !activeRevision.equals(plan.expectedActiveRevisionId())) {
                return PublicationStatus.ACTIVE_REVISION_CHANGED;
            }
            activeRevision = plan.revisionId();
            watermark = plan.watermark();
            this.publishedAt = publishedAt;
            tombstoneCount = plan.tombstoneCount();
            return PublicationStatus.PUBLISHED;
        }

        void stage(UUID revisionId, StageValidation validation) {
            stagedRevisions.add(new StagedRevision(revisionId, validation));
        }
    }

    private record StagedRevision(UUID revisionId, StageValidation validation) {
    }

    private record PublicationAttempt(SyncRunLease lease, PublicationPlan plan) {
    }
}
