package com.yrootlab.onmaru.internal.corpus;

import java.util.Map;

public record CorpusChunk(
        String schemaVersion,
        String chunkId,
        String revision,
        String sourceRef,
        boolean tombstone,
        String hash,
        Map<String, Object> payload
) {
}
