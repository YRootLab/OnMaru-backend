package com.yrootlab.onmaru.catalog.application.query.hanok;

import java.util.Optional;
import java.util.UUID;

public record HanokListQuery(
        String keyword,
        String regionCode,
        HanokListCategory category,
        boolean hasImage,
        int limit,
        String cursor,
        Optional<UUID> memberId) {

    public static HanokListQuery firstPage(int limit, Optional<UUID> memberId) {
        return new HanokListQuery(null, null, null, false, limit, null, memberId);
    }
}
