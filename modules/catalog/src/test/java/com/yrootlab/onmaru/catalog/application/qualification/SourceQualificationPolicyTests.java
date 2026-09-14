package com.yrootlab.onmaru.catalog.application.qualification;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static java.util.Map.entry;

class SourceQualificationPolicyTests {

    private final SourceQualificationPolicy policy = SourceQualificationPolicy.withDefaultAllowlist();

    @Test
    void qualifiesSupportedCategoryWithValidKoreaCoordinatesAsCanonicalCandidate() {
        SourceRecord row = row(Map.of(
                "contentid", "126508",
                "contenttypeid", "12",
                "title", "북촌 한옥마을",
                "cat1", "A02",
                "cat2", "A0201",
                "cat3", "A02010700",
                "mapx", "126.984900",
                "mapy", "37.582604",
                "modifiedtime", "20260914030100"
        ));

        QualificationResult result = policy.qualify(row);

        assertThat(result.status()).isEqualTo(QualificationStatus.CANDIDATE);
        assertThat(result.candidate()).isPresent();
        assertThat(result.candidate().orElseThrow().category()).isEqualTo(CanonicalCategory.HANOK);
        assertThat(result.candidate().orElseThrow().allowlistVersion()).isEqualTo("tourapi-category-allowlist-v1");
        assertThat(result.candidate().orElseThrow().normalizedHash()).hasSize(64);
        assertThat(result.quarantine()).isEmpty();
    }

    @Test
    void normalizedHashIgnoresVolatileProviderFields() {
        SourceRecord base = row(Map.of(
                "contentid", "126508",
                "contenttypeid", "12",
                "title", "북촌 한옥마을",
                "cat1", "A02",
                "cat2", "A0201",
                "cat3", "A02010700",
                "mapx", "126.984900",
                "mapy", "37.582604",
                "modifiedtime", "20260914030100"
        ));
        SourceRecord laterCapture = row(Map.of(
                "contentid", "126508",
                "contenttypeid", "12",
                "title", "북촌 한옥마을",
                "cat1", "A02",
                "cat2", "A0201",
                "cat3", "A02010700",
                "mapx", "126.984900",
                "mapy", "37.582604",
                "modifiedtime", "20260915030100"
        ));

        String baseHash = policy.qualify(base).candidate().orElseThrow().normalizedHash();
        String laterHash = policy.qualify(laterCapture).candidate().orElseThrow().normalizedHash();

        assertThat(laterHash).isEqualTo(baseHash);
    }

    @Test
    void quarantinesUnsupportedCategoryWithoutPublicCandidate() {
        SourceRecord row = row(Map.of(
                "contentid", "200001",
                "contenttypeid", "12",
                "title", "비허용 일반 관광지",
                "cat1", "A01",
                "cat2", "A0101",
                "cat3", "A01010100",
                "mapx", "126.9",
                "mapy", "37.5"
        ));

        QualificationResult result = policy.qualify(row);

        assertThat(result.status()).isEqualTo(QualificationStatus.QUARANTINED);
        assertThat(result.candidate()).isEmpty();
        assertThat(result.quarantine()).isPresent();
        assertThat(result.quarantine().orElseThrow().errorCode()).isEqualTo("UNSUPPORTED_CATEGORY");
    }

    @Test
    void quarantinesMissingOrInvalidCoordinatesWithoutPublicCandidate() {
        SourceRecord missingCoordinates = row(Map.of(
                "contentid", "126508",
                "contenttypeid", "12",
                "title", "북촌 한옥마을",
                "cat1", "A02",
                "cat2", "A0201",
                "cat3", "A02010700"
        ));
        SourceRecord outsideKorea = row(Map.of(
                "contentid", "126508",
                "contenttypeid", "12",
                "title", "북촌 한옥마을",
                "cat1", "A02",
                "cat2", "A0201",
                "cat3", "A02010700",
                "mapx", "-122.4194",
                "mapy", "37.7749"
        ));

        assertThat(policy.qualify(missingCoordinates).quarantine().orElseThrow().errorCode())
                .isEqualTo("MISSING_COORDINATES");
        assertThat(policy.qualify(outsideKorea).quarantine().orElseThrow().errorCode())
                .isEqualTo("INVALID_COORDINATES");
    }

    @Test
    void quarantinePayloadHashIsStableAndRedactedPayloadRemovesSecretMaterial() {
        SourceRecord row = row(Map.ofEntries(
                entry("contentid", "200001"),
                entry("contenttypeid", "12"),
                entry("title", "비허용 일반 관광지"),
                entry("cat1", "A01"),
                entry("cat2", "A0101"),
                entry("cat3", "A01010100"),
                entry("mapx", "126.9"),
                entry("mapy", "37.5"),
                entry("serviceKey", "REAL_PROVIDER_KEY"),
                entry("access_token", "REAL_ACCESS_TOKEN"),
                entry("requestUrl", "https://apis.data.go.kr/B551011/KorService2?serviceKey=REAL_PROVIDER_KEY")
        ));

        QuarantineRecord quarantine = policy.qualify(row).quarantine().orElseThrow();

        assertThat(quarantine.recordKey()).isEqualTo("kto-tourapi-korean:areaBasedList2:200001");
        assertThat(quarantine.payloadHash()).hasSize(64);
        assertThat(quarantine.redactedPayload().toString())
                .doesNotContain("REAL_PROVIDER_KEY")
                .doesNotContain("REAL_ACCESS_TOKEN");
        assertThat(quarantine.redactedPayload())
                .containsEntry("serviceKey", "<REDACTED>")
                .containsEntry("access_token", "<REDACTED>");
    }

    private SourceRecord row(Map<String, String> fields) {
        return new SourceRecord("kto-tourapi-korean", "areaBasedList2", fields);
    }
}
