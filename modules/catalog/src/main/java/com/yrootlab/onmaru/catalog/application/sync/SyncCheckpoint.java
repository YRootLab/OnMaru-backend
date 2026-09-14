package com.yrootlab.onmaru.catalog.application.sync;

import java.util.UUID;

public record SyncCheckpoint(
        UUID runId,
        String partitionKey,
        int nextPage,
        long seenCount,
        String lastSourceModified,
        String lastExternalId
) {
}
