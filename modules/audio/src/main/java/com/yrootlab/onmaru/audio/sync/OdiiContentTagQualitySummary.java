package com.yrootlab.onmaru.audio.sync;

import com.yrootlab.onmaru.catalog.application.tags.ContentTagQualityReport;

import java.util.List;

public record OdiiContentTagQualitySummary(
        int storyCount,
        int emptyStoryCount,
        int lowConfidenceStoryCount,
        int removedGenericCount,
        int hiddenOverrideCount,
        int pinnedOverrideCount
) {

    public static OdiiContentTagQualitySummary empty() {
        return new OdiiContentTagQualitySummary(0, 0, 0, 0, 0, 0);
    }

    public static OdiiContentTagQualitySummary from(List<OdiiStoryVersion> stories) {
        if (stories == null || stories.isEmpty()) {
            return empty();
        }
        int emptyStoryCount = 0;
        int lowConfidenceStoryCount = 0;
        int removedGenericCount = 0;
        int hiddenOverrideCount = 0;
        int pinnedOverrideCount = 0;
        for (OdiiStoryVersion story : stories) {
            ContentTagQualityReport report = story.contentTagQualityReport();
            if (report.emptyResult()) {
                emptyStoryCount++;
            }
            if (report.lowConfidence()) {
                lowConfidenceStoryCount++;
            }
            removedGenericCount += report.removedGenericCount();
            hiddenOverrideCount += report.hiddenOverrideCount();
            pinnedOverrideCount += report.pinnedOverrideCount();
        }
        return new OdiiContentTagQualitySummary(
                stories.size(),
                emptyStoryCount,
                lowConfidenceStoryCount,
                removedGenericCount,
                hiddenOverrideCount,
                pinnedOverrideCount);
    }
}
