package com.yrootlab.onmaru.tourism.audio.client;

import java.util.List;

public record OdiiPage(
        List<OdiiSourceItem> items,
        int page,
        int pageSize,
        long totalCount,
        boolean lastPage
) {

    public OdiiPage {
        items = List.copyOf(items);
    }
}
