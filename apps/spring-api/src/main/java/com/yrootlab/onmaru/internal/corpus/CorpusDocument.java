package com.yrootlab.onmaru.internal.corpus;

import java.util.LinkedHashMap;

public record CorpusDocument(
        String contractVersion,
        String revisionId,
        String documentId,
        String kind,
        String sourceId,
        String sourceRevision,
        String provenanceId,
        String category,
        String region,
        boolean publicationEligible,
        String text,
        String documentHash
) {
    static CorpusDocument create(
            String revisionId,
            String documentId,
            String kind,
            String sourceId,
            String sourceRevision,
            String provenanceId,
            String category,
            String region,
            boolean publicationEligible,
            String text
    ) {
        String contractVersion = "1.0";
        var payload = new LinkedHashMap<String, Object>();
        payload.put("contractVersion", contractVersion);
        payload.put("revisionId", revisionId);
        payload.put("documentId", documentId);
        payload.put("kind", kind);
        payload.put("sourceId", sourceId);
        payload.put("sourceRevision", sourceRevision);
        payload.put("provenanceId", provenanceId);
        payload.put("category", category);
        payload.put("region", region);
        payload.put("publicationEligible", publicationEligible);
        payload.put("text", text);
        String hash = CorpusHashes.prefixedHash(payload);
        return new CorpusDocument(
                contractVersion,
                revisionId,
                documentId,
                kind,
                sourceId,
                sourceRevision,
                provenanceId,
                category,
                region,
                publicationEligible,
                text,
                hash);
    }
}
