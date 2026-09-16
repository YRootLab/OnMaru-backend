package com.yrootlab.onmaru.internal.corpus;

public record CorpusChunkDescriptor(
        String chunkId,
        String sourceRef,
        String hash,
        boolean tombstone
) {
}
