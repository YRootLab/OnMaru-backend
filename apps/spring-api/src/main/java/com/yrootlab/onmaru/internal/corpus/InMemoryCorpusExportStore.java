package com.yrootlab.onmaru.internal.corpus;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

public final class InMemoryCorpusExportStore {

    private final Map<String, CorpusManifest> manifests = new LinkedHashMap<>();
    private final Map<String, Map<String, CorpusDocument>> documents = new LinkedHashMap<>();
    private final Map<String, CorpusAcknowledgement> acknowledgements = new LinkedHashMap<>();

    synchronized Optional<CorpusManifest> manifest(String revisionId) {
        return Optional.ofNullable(manifests.get(revisionId));
    }

    synchronized Optional<CorpusDocument> document(String revisionId, String documentId) {
        return Optional.ofNullable(documents.getOrDefault(revisionId, Map.of()).get(documentId));
    }

    synchronized void save(CorpusManifest manifest, Map<String, CorpusDocument> revisionDocuments) {
        manifests.put(manifest.revisionId(), manifest);
        documents.put(manifest.revisionId(), Map.copyOf(revisionDocuments));
    }

    synchronized void acknowledge(CorpusAcknowledgement acknowledgement) {
        acknowledgements.put(acknowledgement.revisionId(), acknowledgement);
    }

    synchronized Optional<CorpusAcknowledgement> acknowledgement(String revisionId) {
        return Optional.ofNullable(acknowledgements.get(revisionId));
    }
}
