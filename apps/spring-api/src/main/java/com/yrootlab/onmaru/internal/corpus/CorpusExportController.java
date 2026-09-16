package com.yrootlab.onmaru.internal.corpus;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.NoSuchElementException;

@RestController
public final class CorpusExportController {

    private final CorpusExportService corpusExportService;

    CorpusExportController(CorpusExportService corpusExportService) {
        this.corpusExportService = corpusExportService;
    }

    @GetMapping("/internal/v1/corpus/revisions/{revisionId}/manifest")
    ResponseEntity<CorpusManifest> manifest(@PathVariable String revisionId) {
        try {
            return ResponseEntity.ok()
                    .cacheControl(CacheControl.noStore())
                    .body(corpusExportService.manifest(revisionId));
        } catch (NoSuchElementException exception) {
            return ResponseEntity.notFound()
                    .cacheControl(CacheControl.noStore())
                    .build();
        }
    }

    @GetMapping("/internal/v1/corpus/revisions/{revisionId}/documents/{documentId}")
    ResponseEntity<CorpusDocument> document(
            @PathVariable String revisionId,
            @PathVariable String documentId
    ) {
        try {
            return ResponseEntity.ok()
                    .cacheControl(CacheControl.noStore())
                    .body(corpusExportService.document(revisionId, documentId));
        } catch (NoSuchElementException exception) {
            return ResponseEntity.notFound()
                    .cacheControl(CacheControl.noStore())
                    .build();
        }
    }

    @PostMapping("/internal/v1/corpus/revisions/{revisionId}/ack")
    ResponseEntity<Void> acknowledge(
            @PathVariable String revisionId,
            @RequestBody CorpusAcknowledgement acknowledgement
    ) {
        if (!revisionId.equals(acknowledgement.revisionId())) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .cacheControl(CacheControl.noStore())
                    .build();
        }
        try {
            corpusExportService.acknowledge(acknowledgement);
        } catch (CorpusManifestMismatchException | NoSuchElementException exception) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .cacheControl(CacheControl.noStore())
                    .build();
        }
        return ResponseEntity.accepted()
                .cacheControl(CacheControl.noStore())
                .build();
    }
}
