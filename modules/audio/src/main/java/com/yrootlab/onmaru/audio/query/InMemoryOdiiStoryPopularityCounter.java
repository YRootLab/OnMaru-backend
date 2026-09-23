package com.yrootlab.onmaru.audio.query;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/** 로컬·테스트용 인메모리 인기 카운터. 운영에서는 JDBC 구현이 대체한다. */
public final class InMemoryOdiiStoryPopularityCounter implements OdiiStoryPopularityPort {

    private final Map<String, List<Instant>> plays = new ConcurrentHashMap<>();
    private final Map<String, List<Instant>> saves = new ConcurrentHashMap<>();

    @Override
    public void recordPlay(String storyId, Instant occurredAt) {
        plays.computeIfAbsent(storyId, key -> new CopyOnWriteArrayList<>()).add(occurredAt);
    }

    @Override
    public void recordSave(String storyId, Instant occurredAt) {
        saves.computeIfAbsent(storyId, key -> new CopyOnWriteArrayList<>()).add(occurredAt);
    }

    @Override
    public Map<String, Long> playCounts(Collection<String> storyIds, Instant sinceInclusive) {
        return counts(plays, storyIds, sinceInclusive);
    }

    @Override
    public Map<String, Long> saveCounts(Collection<String> storyIds, Instant sinceInclusive) {
        return counts(saves, storyIds, sinceInclusive);
    }

    private Map<String, Long> counts(
            Map<String, List<Instant>> source,
            Collection<String> storyIds,
            Instant sinceInclusive) {
        var result = new java.util.LinkedHashMap<String, Long>();
        for (String storyId : storyIds) {
            result.put(storyId, source.getOrDefault(storyId, List.of()).stream()
                    .filter(at -> !at.isBefore(sinceInclusive))
                    .count());
        }
        return result;
    }

    public void clear() {
        plays.clear();
        saves.clear();
    }
}
