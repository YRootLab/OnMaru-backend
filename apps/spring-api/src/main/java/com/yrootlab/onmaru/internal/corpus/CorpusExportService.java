package com.yrootlab.onmaru.internal.corpus;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class CorpusExportService {

    private final InMemoryCorpusExportStore store;

    public CorpusExportService(InMemoryCorpusExportStore store) {
        this.store = store;
    }

    public CorpusManifest publish(CorpusRevision revision) {
        var candidate = manifestFor(revision);
        var existing = store.manifest(revision.revisionId());
        if (existing.isPresent()) {
            if (existing.orElseThrow().equals(candidate)) {
                return existing.orElseThrow();
            }
            throw new CorpusRevisionConflictException();
        }
        store.save(candidate, documentsById(revision.documents()));
        return candidate;
    }

    public CorpusManifest manifest(String revisionId) {
        return store.manifest(revisionId).orElseThrow();
    }

    public CorpusDocument document(String revisionId, String documentId) {
        return store.document(revisionId, documentId).orElseThrow();
    }

    public void acknowledge(CorpusAcknowledgement acknowledgement) {
        var manifest = manifest(acknowledgement.revisionId());
        if (!manifest.manifestHash().equals(acknowledgement.manifestHash())) {
            throw new CorpusManifestMismatchException();
        }
        store.acknowledge(acknowledgement);
    }

    private CorpusManifest manifestFor(CorpusRevision revision) {
        var entries = new java.util.ArrayList<CorpusManifestEntry>();
        for (CorpusDocument document : revision.documents()) {
            entries.add(new CorpusManifestEntry(
                    document.documentId(),
                    document.kind(),
                    document.documentHash(),
                    "UPSERT",
                    "/internal/v1/corpus/revisions/" + revision.revisionId()
                            + "/documents/" + document.documentId()));
        }
        for (CorpusTombstone tombstone : revision.tombstones()) {
            entries.add(new CorpusManifestEntry(
                    tombstone.documentId(),
                    tombstone.kind(),
                    null,
                "TOMBSTONE",
                null));
        }
        String contractVersion = "1.0";
        return new CorpusManifest(
                contractVersion,
                revision.revisionId(),
                revision.publishedAt(),
                CorpusHashes.prefixedHash(Map.of(
                        "contractVersion", contractVersion,
                        "revisionId", revision.revisionId(),
                        "publishedAt", revision.publishedAt().toString(),
                        "documents", entries.stream().map(this::entryPayload).toList())),
                entries);
    }

    private Map<String, Object> entryPayload(CorpusManifestEntry entry) {
        var payload = new LinkedHashMap<String, Object>();
        payload.put("contentUrl", entry.contentUrl());
        payload.put("documentHash", entry.documentHash());
        payload.put("documentId", entry.documentId());
        payload.put("kind", entry.kind());
        payload.put("state", entry.state());
        return payload;
    }

    private Map<String, CorpusDocument> documentsById(List<CorpusDocument> documents) {
        var byId = new LinkedHashMap<String, CorpusDocument>();
        for (CorpusDocument document : documents) {
            byId.put(document.documentId(), document);
        }
        return byId;
    }
}
