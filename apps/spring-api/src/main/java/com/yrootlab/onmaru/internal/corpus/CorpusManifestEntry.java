package com.yrootlab.onmaru.internal.corpus;

public record CorpusManifestEntry(
        String documentId,
        String kind,
        String documentHash,
        String state,
        String contentUrl
) {
}
