package com.yrootlab.onmaru.audio.sync;

import java.util.Objects;
import java.util.UUID;

public record ActiveAudioRevision(
        UUID revisionId,
        AudioRevisionSnapshot snapshot
) {
    public ActiveAudioRevision {
        Objects.requireNonNull(revisionId, "revisionId");
        snapshot = Objects.requireNonNull(snapshot, "snapshot").copy();
    }

    @Override
    public AudioRevisionSnapshot snapshot() {
        return snapshot.copy();
    }
}
