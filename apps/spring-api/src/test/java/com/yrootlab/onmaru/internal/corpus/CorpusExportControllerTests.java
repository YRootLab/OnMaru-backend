package com.yrootlab.onmaru.internal.corpus;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class CorpusExportControllerTests {

    private static final String REVISION = UUID.fromString("10000000-0000-0000-0000-000000000001").toString();
    private static final String DOCUMENT_ID = "place:one:" + REVISION;

    @Test
    void exposesRevisionPinnedManifestAndDocumentWithoutCaching() throws Exception {
        var mockMvc = MockMvcBuilders.standaloneSetup(new CorpusExportController(service())).build();

        mockMvc.perform(get("/internal/v1/corpus/revisions/{revisionId}/manifest", REVISION))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.contractVersion").value("1.0"))
                .andExpect(jsonPath("$.revisionId").value(REVISION))
                .andExpect(jsonPath("$.publishedAt").value("2026-09-16T04:00:00Z"))
                .andExpect(jsonPath("$.documents[0].documentId").value(DOCUMENT_ID))
                .andExpect(jsonPath("$.documents[0].state").value("UPSERT"));

        mockMvc.perform(get(
                        "/internal/v1/corpus/revisions/{revisionId}/documents/{documentId}",
                        REVISION,
                        DOCUMENT_ID))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.documentId").value(DOCUMENT_ID))
                .andExpect(jsonPath("$.text").value("한옥 설명"));
    }

    @Test
    void recordsAcknowledgementThroughContractEndpoint() throws Exception {
        var service = service();
        var mockMvc = MockMvcBuilders.standaloneSetup(new CorpusExportController(service)).build();
        var manifest = service.manifest(REVISION);

        mockMvc.perform(post("/internal/v1/corpus/revisions/{revisionId}/ack", REVISION)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "contractVersion": "1.0",
                                  "revisionId": "%s",
                                  "manifestHash": "%s",
                                  "status": "ACTIVE",
                                  "documentCount": 1,
                                  "tombstoneCount": 0,
                                  "embeddingProfile": "none",
                                  "completedAt": "2026-09-16T04:00:30Z",
                                  "errorCode": null
                                }
                                """.formatted(REVISION, manifest.manifestHash())))
                .andExpect(status().isAccepted())
                .andExpect(header().string("Cache-Control", "no-store"));
    }

    @Test
    void rejectsAcknowledgementWhenManifestHashDoesNotMatch() throws Exception {
        var mockMvc = MockMvcBuilders.standaloneSetup(new CorpusExportController(service())).build();

        mockMvc.perform(post("/internal/v1/corpus/revisions/{revisionId}/ack", REVISION)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "contractVersion": "1.0",
                                  "revisionId": "%s",
                                  "manifestHash": "sha256:bad",
                                  "status": "REJECTED",
                                  "documentCount": 0,
                                  "tombstoneCount": 0,
                                  "embeddingProfile": "none",
                                  "completedAt": "2026-09-16T04:00:31Z",
                                  "errorCode": "CORPUS_DOCUMENT_HASH_MISMATCH"
                                }
                                """.formatted(REVISION)))
                .andExpect(status().isConflict())
                .andExpect(header().string("Cache-Control", "no-store"));
    }

    @Test
    void rejectsStaleRevisionPinnedRead() throws Exception {
        var mockMvc = MockMvcBuilders.standaloneSetup(new CorpusExportController(service())).build();

        mockMvc.perform(get("/internal/v1/corpus/revisions/{revisionId}/manifest", "catalog-r0"))
                .andExpect(status().isNotFound())
                .andExpect(header().string("Cache-Control", "no-store"));

        mockMvc.perform(get(
                        "/internal/v1/corpus/revisions/{revisionId}/documents/{documentId}",
                        "catalog-r0",
                        DOCUMENT_ID))
                .andExpect(status().isNotFound())
                .andExpect(header().string("Cache-Control", "no-store"));
    }

    private CorpusExportService service() {
        var store = new InMemoryCorpusExportStore();
        var service = new CorpusExportService(store);
        service.publish(new CorpusRevision(
                REVISION,
                Instant.parse("2026-09-16T04:00:00Z"),
                java.util.List.of(CorpusDocument.create(
                        REVISION,
                        DOCUMENT_ID,
                        "PLACE",
                        "opaque-place-1",
                        "source-17",
                        "kto-korean-tour",
                        "HANOK",
                        "11",
                        true,
                        "한옥 설명")),
                java.util.List.of()));
        return service;
    }
}
