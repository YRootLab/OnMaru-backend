package com.yrootlab.onmaru.internal.ai.revocation;

public record AiSourceRevocationSnapshot(
        String contractVersion,
        String snapshotId,
        long watermark,
        String activeRevisionId,
        long publicPlaceCount,
        boolean historyComplete) { }
