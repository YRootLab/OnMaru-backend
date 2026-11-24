package com.yrootlab.onmaru.persistence.catalog;

import java.util.List;

public record AiSourceRevocationPage(
        String contractVersion,
        String snapshotId,
        long afterSequence,
        long watermark,
        List<AiSourceRevocationEvent> events,
        boolean hasMore) { }
