package com.yrootlab.onmaru.internal.corpus;

import java.time.Instant;

public record CorpusAcknowledgement(
        String contractVersion,
        String revisionId,
        String manifestHash,
        CorpusAcknowledgementStatus status,
        int documentCount,
        int tombstoneCount,
        String embeddingProfile,
        Instant completedAt,
        String errorCode
) {
}
