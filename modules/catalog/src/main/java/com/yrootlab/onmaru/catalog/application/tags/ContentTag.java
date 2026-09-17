package com.yrootlab.onmaru.catalog.application.tags;

public record ContentTag(
        String label,
        double score,
        int position,
        ContentTagSourceType source,
        String algorithmVersion) {
}
