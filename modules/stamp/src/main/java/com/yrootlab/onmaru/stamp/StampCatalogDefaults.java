package com.yrootlab.onmaru.stamp;

import java.util.List;

public final class StampCatalogDefaults {

    private StampCatalogDefaults() {
    }

    public static List<StampDefinition> definitions() {
        return List.of(
                regional("stamp_bukchon", "북촌 한옥마을 인장", "북촌·인사동 일대 한옥 방문",
                        "北村", "Landmark", "#b91c1c", StampRarity.COMMON, "SEOUL", 1),
                regional("stamp_eunpyeong", "은평 북한산 고요", "은평 한옥마을 일대 방문",
                        "恩平", "Mountain", "#0f766e", StampRarity.COMMON, "SEOUL", 2),
                regional("stamp_hwaseong", "수원 화성 행궁첩", "수원 화성 및 행궁동 한옥 방문",
                        "華城", "Castle", "#b45309", StampRarity.COMMON, "GYEONGGI", 3),
                regional("stamp_gangneung", "강릉 선교장 만석루", "강릉 선교장 및 오죽헌 일대 방문",
                        "船橋", "Building", "#1e40af", StampRarity.REGIONAL, "GANGWON", 4),
                regional("stamp_oeam", "아산 외암 돌담길", "아산 외암민속마을 일대 방문",
                        "巍岩", "Compass", "#4338ca", StampRarity.COMMON, "CHUNGCHEONG", 5),
                regional("stamp_jeonju", "전주 경기전 태조인", "전주 한옥마을 일대 방문",
                        "全州", "Crown", "#991b1b", StampRarity.REGIONAL, "JEOLLA", 6),
                regional("stamp_unjoru", "지리산 구례 운조루", "구례 운조루 및 지리산 일대 한옥 방문",
                        "雲鳥", "HeartHandshake", "#065f46", StampRarity.RARE, "JEOLLA", 7),
                regional("stamp_andong", "안동 하회 부용대", "안동 하회마을 및 도산서원 방문",
                        "河回", "Scroll", "#831843", StampRarity.REGIONAL, "GYEONGSANG", 8),
                regional("stamp_yangdong", "경주 양동 유네스코", "경주 양동마을 및 교촌 한옥마을 방문",
                        "良洞", "Sparkles", "#7c2d12", StampRarity.REGIONAL, "GYEONGSANG", 9),
                regional("stamp_jeju_seongup", "탐라 성읍 흙담집", "제주 성읍민속마을 일대 방문",
                        "城邑", "Palmtree", "#15803d", StampRarity.REGIONAL, "JEJU", 10),
                new StampDefinition("stamp_night_hanok", "달빛 고택 야행인",
                        "은은한 달빛이 처마 끝에 내려앉는 고요한 밤의 정취를 담다.",
                        "오후 6시 이후 또는 오전 6시 이전 한옥 명소 방문", "夜景", "Moon", "#6d28d9",
                        StampRarity.RARE, StampConditionType.NIGHT_VISIT, null, null, 11, true),
                new StampDefinition("stamp_national_master", "팔도 유람 팔도어보",
                        "삼천리 강산의 한옥을 두루 유람한 온마루 풍류객의 수결이다.",
                        "서로 다른 5개 권역 한옥 방문", "八道", "Trophy", "#d4af37",
                        StampRarity.LEGENDARY, StampConditionType.REGION_COUNT, 5, null, 12, true));
    }

    public static List<StampRegionRule> regionRules() {
        return List.of(
                new StampRegionRule("stamp_bukchon", "kr-11-jongno"),
                new StampRegionRule("stamp_eunpyeong", "kr-11-eunpyeong"),
                new StampRegionRule("stamp_hwaseong", "kr-41-suwon"),
                new StampRegionRule("stamp_gangneung", "kr-42-gangneung"),
                new StampRegionRule("stamp_oeam", "kr-44-asan"),
                new StampRegionRule("stamp_jeonju", "kr-45-jeonju"),
                new StampRegionRule("stamp_unjoru", "kr-46-gurye"),
                new StampRegionRule("stamp_andong", "kr-47-andong"),
                new StampRegionRule("stamp_yangdong", "kr-47-gyeongju"),
                new StampRegionRule("stamp_jeju_seongup", "kr-50-seogwipo"));
    }

    private static StampDefinition regional(
            String code, String name, String condition, String seal, String icon, String color,
            StampRarity rarity, String group, int order) {
        return new StampDefinition(code, name, condition, condition, seal, icon, color, rarity,
                StampConditionType.REGION_VISIT, null, group, order, true);
    }
}
