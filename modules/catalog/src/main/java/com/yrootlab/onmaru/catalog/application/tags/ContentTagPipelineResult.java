package com.yrootlab.onmaru.catalog.application.tags;

import java.util.List;

public record ContentTagPipelineResult(
        List<String> publicLabels,
        List<ContentTag> rankedTags,
        String sourceHash,
        String algorithmVersion,
        ContentTagQualityReport qualityReport
) {

    public ContentTagPipelineResult {
        publicLabels = publicLabels == null ? List.of() : List.copyOf(publicLabels);
        rankedTags = rankedTags == null ? List.of() : List.copyOf(rankedTags);
        if (sourceHash == null || sourceHash.isBlank()) {
            throw new IllegalArgumentException("sourceHash must not be blank");
        }
        if (algorithmVersion == null || algorithmVersion.isBlank()) {
            throw new IllegalArgumentException("algorithmVersion must not be blank");
        }
        if (qualityReport == null) {
            throw new IllegalArgumentException("qualityReport must not be null");
        }
    }
}
