package com.yrootlab.onmaru.catalog.application.publication;

import java.time.Clock;

public final class DatasetPublicationService {

    private final PublicationStore store;
    private final Clock clock;

    public DatasetPublicationService(PublicationStore store, Clock clock) {
        this.store = store;
        this.clock = clock;
    }

    public PublicationResult publish(PublicationCommand command) {
        var validation = store.stageValidation(command.stagedRevisionId());
        if (validation.isEmpty() || !validation.orElseThrow().ready()) {
            return new PublicationResult(PublicationStatus.STAGE_INCOMPLETE);
        }
        if (command.mode() == PublicationMode.FULL
                && validation.orElseThrow().rowCount() == 0
                && !validation.orElseThrow().emptyFullSyncReviewed()) {
            return new PublicationResult(PublicationStatus.EMPTY_FULL_SYNC_REQUIRES_REVIEW);
        }

        var plan = new PublicationPlan(
                command.stagedRevisionId(),
                command.expectedActiveRevisionId(),
                command.watermark(),
                command.tombstoneCount()
        );
        return new PublicationResult(store.publishIfLeaseAndBaseRevisionMatch(command.lease(), plan, clock.instant()));
    }
}
