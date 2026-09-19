package com.yrootlab.onmaru.audio.sync;

import com.yrootlab.onmaru.catalog.application.sync.SyncRunLease;

import java.util.List;
import java.util.UUID;

public record OdiiSyncCommand(
        String dataset,
        SyncRunLease lease,
        UUID expectedActiveRevisionId,
        List<String> languages,
        boolean emptyFullSyncReviewed
) {

    public OdiiSyncCommand {
        if (dataset == null || dataset.isBlank()) {
            throw new IllegalArgumentException("dataset must not be blank");
        }
        if (lease == null || expectedActiveRevisionId == null) {
            throw new IllegalArgumentException("lease and expectedActiveRevisionId are required");
        }
        languages = List.copyOf(languages);
        if (languages.isEmpty() || languages.stream().anyMatch(language -> language == null || language.isBlank())) {
            throw new IllegalArgumentException("languages must contain non-blank values");
        }
        if (languages.stream().distinct().count() != languages.size()) {
            throw new IllegalArgumentException("languages must be unique");
        }
    }
}
