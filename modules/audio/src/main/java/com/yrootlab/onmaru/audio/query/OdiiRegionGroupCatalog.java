package com.yrootlab.onmaru.audio.query;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 광역 지역 그룹 매핑 규칙.
 *
 * <p>오디 스토리의 행정구역 코드는 시·군 단위(예: {@code kr-45-jeonju})이고,
 * 상위 지역 코드(예: {@code kr-45})의 두 자리 광역 코드로 그룹을 묶는다.
 * FE "지도로 듣는 이야기" 화면의 지역 탭 순서를 이 카탈로그의 선언 순서로 사용한다.</p>
 */
public final class OdiiRegionGroupCatalog {

    private static final List<String> GROUP_LABELS = List.of(
            "서울·경기·인천",
            "강원",
            "충북",
            "충남·대전·세종",
            "경북·대구",
            "전북",
            "전남·광주",
            "경남·부산·울산",
            "제주",
            "기타 지역");

    private static final Map<String, String> PROVINCE_LABELS = Map.ofEntries(
            Map.entry("11", "서울·경기·인천"),
            Map.entry("23", "서울·경기·인천"),
            Map.entry("41", "서울·경기·인천"),
            Map.entry("42", "강원"),
            Map.entry("43", "충북"),
            Map.entry("44", "충남·대전·세종"),
            Map.entry("25", "충남·대전·세종"),
            Map.entry("29", "충남·대전·세종"),
            Map.entry("47", "경북·대구"),
            Map.entry("22", "경북·대구"),
            Map.entry("45", "전북"),
            Map.entry("46", "전남·광주"),
            Map.entry("24", "전남·광주"),
            Map.entry("48", "경남·부산·울산"),
            Map.entry("21", "경남·부산·울산"),
            Map.entry("26", "경남·부산·울산"),
            Map.entry("50", "제주"));

    private static final String UNKNOWN_GROUP_LABEL = "기타 지역";

    private OdiiRegionGroupCatalog() {
    }

    /**
     * 오디 스토리의 행정구역 코드에서 광역 그룹 라벨을 결정한다.
     *
     * @param regionCode         시·군 단위 코드 (예: {@code kr-45-jeonju})
     * @param parentRegionCode   상위 광역 코드 (예: {@code kr-45}), 없으면 null
     * @return 광역 그룹 라벨, 매핑되지 않는 코드는 {@code 기타 지역}
     */
    public static String groupLabel(OdiiRegionRef region) {
        String parent = region.parentRegionCode();
        if (parent != null && !parent.isBlank()) {
            return labelForCode(parent);
        }
        return labelForCode(region.regionCode());
    }

    private static String labelForCode(String code) {
        return Optional.ofNullable(code)
                .filter(value -> value.contains("-"))
                .map(value -> value.substring(value.lastIndexOf('-') + 1))
                .map(PROVINCE_LABELS::get)
                .orElse(UNKNOWN_GROUP_LABEL);
    }

    public static List<String> groupOrder() {
        return GROUP_LABELS;
    }
}
