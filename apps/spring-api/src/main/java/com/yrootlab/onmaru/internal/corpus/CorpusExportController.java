package com.yrootlab.onmaru.internal.corpus;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.NoSuchElementException;

@Tag(name = "00. 내부 시스템 (Internal)", description = "FastAPI 및 내부 AI 서비스와의 지식 코퍼스(Corpus) 동기화 API")
@RestController
public final class CorpusExportController {

    private final CorpusExportService corpusExportService;

    CorpusExportController(CorpusExportService corpusExportService) {
        this.corpusExportService = corpusExportService;
    }

    @Operation(
            summary = "코퍼스 리비전 매니페스트 조회",
            description = "특정 리비전 ID에 포함된 모든 청크 및 문서 메타데이터 목록을 조회합니다.",
            security = @SecurityRequirement(name = "internalSecret")
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "매니페스트 조회 성공", content = @Content(schema = @Schema(implementation = CorpusManifest.class))),
            @ApiResponse(responseCode = "404", description = "해당 리비전이 존재하지 않음", content = @Content)
    })
    @GetMapping("/internal/v1/corpus/revisions/{revisionId}/manifest")
    ResponseEntity<CorpusManifest> manifest(
            @Parameter(description = "코퍼스 리비전 식별자", example = "rev-2026-09-001")
            @PathVariable String revisionId
    ) {
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

    @Operation(
            summary = "코퍼스 단일 문서 내용 조회",
            description = "특정 리비전에 속한 개별 문서의 원문 및 청크 상세 내용을 조회합니다.",
            security = @SecurityRequirement(name = "internalSecret")
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "문서 조회 성공", content = @Content(schema = @Schema(implementation = CorpusDocument.class))),
            @ApiResponse(responseCode = "404", description = "해당 리비전 또는 문서가 존재하지 않음", content = @Content)
    })
    @GetMapping("/internal/v1/corpus/revisions/{revisionId}/documents/{documentId}")
    ResponseEntity<CorpusDocument> document(
            @Parameter(description = "코퍼스 리비전 식별자", example = "rev-2026-09-001")
            @PathVariable String revisionId,
            @Parameter(description = "문서 고유 식별자", example = "doc-hanok-001")
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

    @Operation(
            summary = "코퍼스 동기화 완료 수신확인(ACK)",
            description = "FastAPI AI 서비스가 리비전 문서 색인을 완료한 후 수신 확인(ACK)을 전송합니다.",
            security = @SecurityRequirement(name = "internalSecret")
    )
    @ApiResponses({
            @ApiResponse(responseCode = "202", description = "수신확인 처리 접수 완료"),
            @ApiResponse(responseCode = "409", description = "리비전 불일치 또는 검증 오류", content = @Content)
    })
    @PostMapping("/internal/v1/corpus/revisions/{revisionId}/ack")
    ResponseEntity<Void> acknowledge(
            @Parameter(description = "코퍼스 리비전 식별자", example = "rev-2026-09-001")
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
