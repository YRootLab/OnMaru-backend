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
        assertThat(result.candidate().orElseThrow().allowlistVersion()).isEqualTo("tourapi-category-allowlist-v4");
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
    void qualifiesHistoricTourismSiteUnderCat2WildcardAsHistoricSite() {
        SourceRecord row = row(Map.of(
                "contentid", "126508",
                "contenttypeid", "12",
                "title", "경복궁",
                "cat1", "A02",
                "cat2", "A0201",
                "cat3", "A02010100",
                "mapx", "126.976993",
                "mapy", "37.578822",
                "modifiedtime", "20260914030100"
        ));

        QualificationResult result = policy.qualify(row);

        assertThat(result.status()).isEqualTo(QualificationStatus.CANDIDATE);
        assertThat(result.candidate().orElseThrow().category()).isEqualTo(CanonicalCategory.HISTORIC_SITE);
        assertThat(result.quarantine()).isEmpty();
    }

    @Test
    void qualifiesTraditionalMarketSiblingCategoryUnderCat2WildcardAsTraditionalMarket() {
        SourceRecord row = row(Map.of(
                "contentid", "2000501",
                "contenttypeid", "38",
                "title", "통인시장",
                "cat1", "A04",
                "cat2", "A0401",
                "cat3", "A04010100",
                "mapx", "126.970000",
                "mapy", "37.580000",
                "modifiedtime", "20260914030100"
        ));

        QualificationResult result = policy.qualify(row);

        assertThat(result.status()).isEqualTo(QualificationStatus.CANDIDATE);
        assertThat(result.candidate().orElseThrow().category()).isEqualTo(CanonicalCategory.TRADITIONAL_MARKET);
        assertThat(result.quarantine()).isEmpty();
    }

    @Test
    void exactCat3EntryTakesPrecedenceOverCat2Wildcard() {
        SourceRecord row = row(Map.of(
                "contentid", "126509",
                "contenttypeid", "12",
                "title", "한옥 정자",
                "cat1", "A02",
                "cat2", "A0201",
                "cat3", "A02010700",
                "mapx", "126.984900",
                "mapy", "37.582604"
        ));

        QualificationResult result = policy.qualify(row);

        assertThat(result.status()).isEqualTo(QualificationStatus.CANDIDATE);
        assertThat(result.candidate().orElseThrow().category()).isEqualTo(CanonicalCategory.HANOK);
    }

    @Test
    void qualifiesNatureTourismSiteUnderCat2WildcardAsNatureSite() {
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

        assertThat(result.status()).isEqualTo(QualificationStatus.CANDIDATE);
        assertThat(result.candidate().orElseThrow().category()).isEqualTo(CanonicalCategory.NATURE_SITE);
        assertThat(result.quarantine()).isEmpty();
    }

    @Test
    void qualifiesLeisureActivityUnderCat2WildcardAsLeisureActivity() {
        SourceRecord row = row(Map.of(
                "contentid", "300001",
                "contenttypeid", "12",
                "title", "전통 체험 마을",
                "cat1", "A03",
                "cat2", "A0301",
                "cat3", "A03010100",
                "mapx", "127.1",
                "mapy", "35.8"
        ));

        QualificationResult result = policy.qualify(row);

        assertThat(result.status()).isEqualTo(QualificationStatus.CANDIDATE);
        assertThat(result.candidate().orElseThrow().category()).isEqualTo(CanonicalCategory.LEISURE_ACTIVITY);
        assertThat(result.quarantine()).isEmpty();
    }

    @Test
    void qualifiesOnlyReviewedCultureFoodGardenAndLocalSceneCodes() {
        assertThat(qualify("14", "A02", "A0206", "A02060100"))
                .isEqualTo(CanonicalCategory.CULTURE_ART);
        assertThat(qualify("39", "A05", "A0502", "A05020100"))
                .isEqualTo(CanonicalCategory.TRADITIONAL_FOOD);
        assertThat(qualify("12", "A01", "A0101", "A01010700"))
                .isEqualTo(CanonicalCategory.GARDEN_ECOLOGY);
        assertThat(qualify("12", "A02", "A0203", "A02030100"))
                .isEqualTo(CanonicalCategory.LOCAL_SCENE);
        assertThat(policy.qualify(row(Map.of(
                "contentid", "unsupported",
                "contenttypeid", "14",
                "title", "미검수 문화시설",
                "cat1", "A02",
                "cat2", "A0206",
                "cat3", "A02061000",
                "mapx", "126.9",
                "mapy", "37.5"
        ))).quarantine().orElseThrow().errorCode()).isEqualTo("UNSUPPORTED_CATEGORY");
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
                entry("cat1", "A99"),
                entry("cat2", "A9901"),
                entry("cat3", "A99010100"),
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

    private CanonicalCategory qualify(String contentTypeId, String cat1, String cat2, String cat3) {
        return policy.qualify(row(Map.of(
                "contentid", cat3,
                "contenttypeid", contentTypeId,
                "title", "검수 카테고리",
                "cat1", cat1,
                "cat2", cat2,
                "cat3", cat3,
                "mapx", "126.9",
                "mapy", "37.5"
        ))).candidate().orElseThrow().category();
    }
}
