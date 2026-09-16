package com.yrootlab.onmaru.internal.corpus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class CorpusExportServiceTests {

    private static final Instant PUBLISHED_AT = Instant.parse("2026-09-16T04:00:00Z");

    private final InMemoryCorpusExportStore store = new InMemoryCorpusExportStore();
    private final CorpusExportService service = new CorpusExportService(store);

    @Test
    void publishesAnImmutableRevisionPinnedManifestAndSanitizedDocument() {
        var document = CorpusDocument.create(
            "catalog-r1",
            "place:one:catalog-r1",
            "PLACE",
            "opaque-place-1",
            "source-17",
            "kto-korean-tour",
            "HANOK",
            "11",
            true,
            "한옥 설명"
        );

        var published = service.publish(new CorpusRevision(
            "catalog-r1",
            PUBLISHED_AT,
            List.of(document),
            List.of(new CorpusTombstone("place:removed:catalog-r1", "PLACE"))
        ));

        assertThat(document.documentHash())
            .isEqualTo("sha256:2396a8d72fbc8cb98e303b2268ed39233e1de820cd30dc0cdf5b1e671720bd25");
        assertThat(published.manifestHash())
            .isEqualTo("sha256:d9e4a11d5df721b6d78725ab96e08483d99792ad034981005521a7bc5d999d92");
        assertThat(published.documents()).hasSize(2);
        assertThat(service.manifest("catalog-r1")).isEqualTo(published);
        assertThat(service.document("catalog-r1", document.documentId())).isEqualTo(document);
        assertThat(published.documents().get(1).contentUrl()).isNull();
        assertThat(published.documents().get(1).documentHash()).isNull();
    }

    @Test
    void repeatedPublishIsIdempotentButConflictingRevisionIsRejected() {
        var first = revision("catalog-r1", "같은 설명");

        var published = service.publish(first);

        assertThat(service.publish(first)).isEqualTo(published);
        assertThatThrownBy(() -> service.publish(revision("catalog-r1", "바뀐 설명")))
            .isInstanceOf(CorpusRevisionConflictException.class);
        assertThat(service.manifest("catalog-r1")).isEqualTo(published);
    }

    @Test
    void recordsOnlyAcknowledgementThatMatchesPublishedManifest() {
        var manifest = service.publish(revision("catalog-r1", "설명"));
        var acknowledgement = new CorpusAcknowledgement(
            "1.0",
            "catalog-r1",
            manifest.manifestHash(),
            CorpusAcknowledgementStatus.ACTIVE,
            1,
            0,
            "none",
            PUBLISHED_AT.plusSeconds(30),
            null
        );

        service.acknowledge(acknowledgement);

        assertThat(store.acknowledgement("catalog-r1")).contains(acknowledgement);
        assertThatThrownBy(() -> service.acknowledge(new CorpusAcknowledgement(
            "1.0",
            "catalog-r1",
            "sha256:wrong",
            CorpusAcknowledgementStatus.REJECTED,
            0,
            0,
            "none",
            PUBLISHED_AT.plusSeconds(31),
            "CORPUS_HASH_MISMATCH"
        ))).isInstanceOf(CorpusManifestMismatchException.class);
    }

    private CorpusRevision revision(String revisionId, String text) {
        return new CorpusRevision(
            revisionId,
            PUBLISHED_AT,
            List.of(CorpusDocument.create(
                revisionId,
                "place:one:" + revisionId,
                "PLACE",
                "opaque-place-1",
                "source-17",
                "kto-korean-tour",
                "HANOK",
                "11",
                true,
                text
            )),
            List.of()
        );
    }
}
