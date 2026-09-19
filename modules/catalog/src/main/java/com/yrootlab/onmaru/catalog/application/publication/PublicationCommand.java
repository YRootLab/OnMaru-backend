package com.yrootlab.onmaru.catalog.application.publication;

import com.yrootlab.onmaru.catalog.application.sync.SyncRunLease;

import java.util.UUID;

public record PublicationCommand(
        SyncRunLease lease,
        UUID stagedRevisionId,
        UUID expectedActiveRevisionId,
        PublicationMode mode,
        SourceWatermark watermark,
        long tombstoneCount
) {
}
