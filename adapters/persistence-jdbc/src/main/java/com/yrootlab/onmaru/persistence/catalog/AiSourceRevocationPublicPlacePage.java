package com.yrootlab.onmaru.persistence.catalog;

import java.util.List;

public record AiSourceRevocationPublicPlacePage(
        String contractVersion,
        String snapshotId,
        String afterPublicId,
        List<String> publicPlaceIds,
        boolean hasMore) { }
