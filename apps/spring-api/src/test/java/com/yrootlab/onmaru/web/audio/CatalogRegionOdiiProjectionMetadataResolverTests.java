package com.yrootlab.onmaru.web.audio;

import com.yrootlab.onmaru.audio.sync.AudioStatus;
import com.yrootlab.onmaru.audio.sync.OdiiSpotIdentity;
import com.yrootlab.onmaru.audio.sync.OdiiSpotVersion;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class CatalogRegionOdiiProjectionMetadataResolverTests {

    private final CatalogRegionOdiiProjectionMetadataResolver resolver =
            new CatalogRegionOdiiProjectionMetadataResolver("오디오 관광", Optional.empty());

    @Test
    void 전국_대표_좌표를_지도_광역_그룹_코드로_분류한다() {
        assertRegion(37.5665, 126.9780, "kr-11", "서울·경기·인천");
        assertRegion(37.7519, 128.8761, "kr-42", "강원");
        assertRegion(36.6424, 127.4890, "kr-43", "충북");
        assertRegion(36.3504, 127.3845, "kr-44", "충남·대전·세종");
        assertRegion(35.8714, 128.6014, "kr-47", "경북·대구");
        assertRegion(35.8242, 127.1480, "kr-45", "전북");
        assertRegion(35.1595, 126.8526, "kr-46", "전남·광주");
        assertRegion(35.1796, 129.0756, "kr-48", "경남·부산·울산");
        assertRegion(33.4996, 126.5312, "kr-50", "제주");
    }

    private void assertRegion(double latitude, double longitude, String code, String name) {
        var spot = new OdiiSpotVersion(
                new OdiiSpotIdentity("ODII", code, code + "-location", "ko"),
                name,
                BigDecimal.valueOf(longitude),
                BigDecimal.valueOf(latitude),
                Instant.parse("2026-09-28T00:00:00Z"),
                AudioStatus.ACTIVE,
                "hash");

        assertThat(resolver.resolve(spot).region())
                .extracting("regionCode", "name", "level", "parentRegionCode")
                .containsExactly(code, name, "PROVINCE", null);
    }
}
