package com.yrootlab.onmaru.stamp;

import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class StampAwardPolicy {

    private static final ZoneId KOREA = ZoneId.of("Asia/Seoul");

    public List<StampDefinition> newAwards(
            List<StampDefinition> definitions,
            List<StampRegionRule> regionRules,
            String regionCode,
            Instant checkedInAt,
            Set<String> existingCodes) {
        var active = definitions.stream().filter(StampDefinition::active).toList();
        var selected = new ArrayList<StampDefinition>();

        for (var definition : active) {
            if (existingCodes.contains(definition.code())) {
                continue;
            }
            if (definition.conditionType() == StampConditionType.REGION_VISIT
                    && regionRules.stream().anyMatch(rule -> rule.stampCode().equals(definition.code())
                    && rule.matches(regionCode))) {
                selected.add(definition);
            }
            if (definition.conditionType() == StampConditionType.NIGHT_VISIT && isNight(checkedInAt)) {
                selected.add(definition);
            }
        }

        var earnedCodes = new HashSet<>(existingCodes);
        selected.forEach(definition -> earnedCodes.add(definition.code()));
        var regionGroups = active.stream()
                .filter(definition -> definition.conditionType() == StampConditionType.REGION_VISIT)
                .filter(definition -> earnedCodes.contains(definition.code()))
                .map(StampDefinition::regionGroup)
                .filter(group -> group != null)
                .collect(java.util.stream.Collectors.toSet());

        active.stream()
                .filter(definition -> definition.conditionType() == StampConditionType.REGION_COUNT)
                .filter(definition -> !earnedCodes.contains(definition.code()))
                .filter(definition -> regionGroups.size() >= definition.requiredCount())
                .forEach(selected::add);

        return selected.stream().sorted(java.util.Comparator.comparingInt(StampDefinition::sortOrder)).toList();
    }

    public int visitedRegionCount(List<StampDefinition> definitions, Set<String> earnedCodes) {
        return (int) definitions.stream()
                .filter(definition -> definition.conditionType() == StampConditionType.REGION_VISIT)
                .filter(definition -> earnedCodes.contains(definition.code()))
                .map(StampDefinition::regionGroup)
                .filter(group -> group != null)
                .distinct()
                .count();
    }

    private boolean isNight(Instant checkedInAt) {
        int hour = checkedInAt.atZone(KOREA).getHour();
        return hour >= 18 || hour < 6;
    }
}
