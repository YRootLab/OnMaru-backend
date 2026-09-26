package com.yrootlab.onmaru.stamp;

import java.time.Instant;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class InMemoryStampStore implements StampStore {

    private static final ZoneId KOREA = ZoneId.of("Asia/Seoul");
    private static final int DAILY_LIMIT = 30;

    private final List<StampDefinition> definitions;
    private final List<StampRegionRule> regionRules;
    private final StampAwardPolicy policy = new StampAwardPolicy();
    private final Map<CheckInKey, StoredCheckIn> checkIns = new HashMap<>();
    private final Map<UUID, LinkedHashMap<String, StoredAward>> awardsByMember = new HashMap<>();

    public InMemoryStampStore() {
        this(StampCatalogDefaults.definitions(), StampCatalogDefaults.regionRules());
    }

    public InMemoryStampStore(List<StampDefinition> definitions, List<StampRegionRule> regionRules) {
        this.definitions = definitions.stream()
                .sorted(Comparator.comparingInt(StampDefinition::sortOrder))
                .toList();
        this.regionRules = List.copyOf(regionRules);
    }

    @Override
    public synchronized List<StampDefinition> definitions() {
        return definitions.stream().filter(StampDefinition::active).toList();
    }

    @Override
    public synchronized StampCheckInResult record(
            UUID memberId, VerifiedPlace place, Instant now, int accuracyMeters) {
        var bucket = bucket(now);
        var key = new CheckInKey(memberId, place.internalPlaceId(), bucket);
        var existing = checkIns.get(key);
        if (existing != null) {
            return new StampCheckInResult(
                    existing.toPublic(true), List.of(), summary(memberId));
        }
        enforceDailyLimit(memberId, now);

        var stored = new StoredCheckIn(
                UUID.randomUUID(), place.publicPlaceId(), place.internalPlaceId(), place.regionCode(),
                now, bucket, place.distanceMeters(), accuracyMeters);
        checkIns.put(key, stored);

        var memberAwards = awardsByMember.computeIfAbsent(memberId, ignored -> new LinkedHashMap<>());
        var selected = policy.newAwards(
                definitions, regionRules, place.regionCode(), now, Set.copyOf(memberAwards.keySet()));
        var newAwards = new ArrayList<StampAwardSummary>();
        for (var definition : selected) {
            var award = new StoredAward(definition, now, place.publicPlaceId(), stored.id());
            memberAwards.putIfAbsent(definition.code(), award);
            newAwards.add(award.toSummary());
        }
        return new StampCheckInResult(stored.toPublic(false), newAwards, summary(memberId));
    }

    @Override
    public synchronized StampBook book(UUID memberId) {
        var awards = awardsByMember.getOrDefault(memberId, new LinkedHashMap<>());
        var items = definitions().stream().map(definition -> {
            var award = awards.get(definition.code());
            return new StampBookItem(
                    definition.code(), definition.name(), definition.rarity(), definition.regionGroup(),
                    definition.conditionLabel(), definition.description(), definition.sealText(),
                    definition.iconName(), definition.color(), definition.sortOrder(), award != null,
                    award == null ? null : award.collectedAt(),
                    award == null ? null : award.triggerPlaceId());
        }).toList();
        return new StampBook(summary(memberId), items);
    }

    public synchronized void clear() {
        checkIns.clear();
        awardsByMember.clear();
    }

    private StampBookSummary summary(UUID memberId) {
        var codes = Set.copyOf(awardsByMember.getOrDefault(memberId, new LinkedHashMap<>()).keySet());
        int total = definitions().size();
        int collected = codes.size();
        int regionCount = policy.visitedRegionCount(definitions, codes);
        int requiredRegions = definitions.stream()
                .filter(definition -> definition.conditionType() == StampConditionType.REGION_COUNT)
                .map(StampDefinition::requiredCount)
                .filter(value -> value != null)
                .findFirst()
                .orElse(0);
        int completion = total == 0 ? 0 : collected * 100 / total;
        return new StampBookSummary(collected, total, regionCount, requiredRegions, completion);
    }

    private void enforceDailyLimit(UUID memberId, Instant now) {
        var date = now.atZone(KOREA).toLocalDate();
        long count = checkIns.entrySet().stream()
                .filter(entry -> entry.getKey().memberId().equals(memberId))
                .map(entry -> entry.getValue().checkedInAt())
                .filter(checkedAt -> checkedAt.atZone(KOREA).toLocalDate().equals(date))
                .count();
        if (count >= DAILY_LIMIT) {
            var nextDay = date.plusDays(1).atStartOfDay(KOREA).toInstant();
            throw new CheckInRateLimitedException(Math.max(1, ChronoUnit.SECONDS.between(now, nextDay)));
        }
    }

    private Instant bucket(Instant value) {
        long seconds = value.getEpochSecond();
        return Instant.ofEpochSecond(seconds - Math.floorMod(seconds, 900));
    }

    private record CheckInKey(UUID memberId, UUID placeId, Instant bucket) {
    }

    private record StoredCheckIn(
            UUID id,
            String publicPlaceId,
            UUID internalPlaceId,
            String regionCode,
            Instant checkedInAt,
            Instant bucket,
            int distanceMeters,
            int accuracyMeters) {

        StampCheckIn toPublic(boolean repeated) {
            return new StampCheckIn(id, publicPlaceId, checkedInAt, distanceMeters, repeated);
        }
    }

    private record StoredAward(
            StampDefinition definition, Instant collectedAt, String triggerPlaceId, UUID checkInId) {

        StampAwardSummary toSummary() {
            return new StampAwardSummary(
                    definition.code(), definition.name(), definition.sealText(),
                    definition.rarity(), collectedAt);
        }
    }
}
