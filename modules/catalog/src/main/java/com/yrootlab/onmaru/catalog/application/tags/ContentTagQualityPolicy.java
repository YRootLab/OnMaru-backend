package com.yrootlab.onmaru.catalog.application.tags;

public record ContentTagQualityPolicy(
        int lowConfidenceMinimumTagCount,
        int genericHeavyRemovedCount
) {

    public static ContentTagQualityPolicy defaultPolicy() {
        return new ContentTagQualityPolicy(3, 5);
    }

    public ContentTagQualityPolicy {
        if (lowConfidenceMinimumTagCount < 1) {
            throw new IllegalArgumentException("lowConfidenceMinimumTagCount must be positive");
        }
        if (genericHeavyRemovedCount < 1) {
            throw new IllegalArgumentException("genericHeavyRemovedCount must be positive");
        }
    }

    boolean lowConfidence(int finalCount, int maxTags) {
        return finalCount < Math.min(lowConfidenceMinimumTagCount, maxTags);
    }

    boolean genericHeavy(int removedGenericCount) {
        return removedGenericCount >= genericHeavyRemovedCount;
    }
}
