package com.yrootlab.onmaru.catalog.application.publication;

import com.yrootlab.onmaru.catalog.application.sync.SyncRunLease;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface PublicationStore {

    Optional<StageValidation> stageValidation(UUID revisionId);

    PublicationStatus publishIfLeaseAndBaseRevisionMatch(
            SyncRunLease lease,
            PublicationPlan plan,
            Instant publishedAt
    );
}
