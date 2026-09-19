package com.yrootlab.onmaru.internal.corpus;

import java.time.Instant;
import java.util.List;

public record CorpusRevision(
        String revisionId,
        Instant publishedAt,
        List<CorpusDocument> documents,
        List<CorpusTombstone> tombstones
) {
    public CorpusRevision {
        documents = List.copyOf(documents);
        tombstones = List.copyOf(tombstones);
    }
}
