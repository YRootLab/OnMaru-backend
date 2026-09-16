package com.yrootlab.onmaru.internal.corpus;

import java.time.Instant;
import java.util.List;

public record CorpusManifest(
        String contractVersion,
        String revisionId,
        Instant publishedAt,
        String manifestHash,
        List<CorpusManifestEntry> documents
) {
    public CorpusManifest {
        documents = List.copyOf(documents);
    }
}
