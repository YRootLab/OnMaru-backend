package com.yrootlab.onmaru.kcontents.research;

import java.util.List;
import java.util.UUID;

/** Worker output is evidence-bound and remains unpublished until W7 verification. */
public record ResearchSubmission(
        String resultStatus,
        String schemaVersion,
        String promptVersion,
        String modelVersion,
        List<UUID> evidenceIds,
        String resultJson
) {
    public ResearchSubmission {
        evidenceIds = evidenceIds == null ? List.of() : List.copyOf(evidenceIds);
        if (!List.of("MATCH", "NO_MATCH", "UNCERTAIN").contains(resultStatus)) throw new IllegalArgumentException("Invalid resultStatus");
        if (blank(schemaVersion) || blank(promptVersion) || blank(modelVersion) || blank(resultJson))
            throw new IllegalArgumentException("Missing submission metadata");
        if (resultJson.length() > 100_000) throw new IllegalArgumentException("Result too large");
        if ("MATCH".equals(resultStatus) && evidenceIds.isEmpty()) throw new IllegalArgumentException("MATCH requires evidence");
    }

    private static boolean blank(String value) { return value == null || value.isBlank(); }
}
