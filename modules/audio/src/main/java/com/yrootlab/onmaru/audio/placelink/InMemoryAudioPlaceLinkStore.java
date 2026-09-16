package com.yrootlab.onmaru.audio.placelink;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class InMemoryAudioPlaceLinkStore implements AudioPlaceLinkStore {

    private final Map<LinkKey, AudioPlaceLinkCandidate> candidates = new LinkedHashMap<>();

    @Override
    public synchronized AudioPlaceLinkCandidate saveIfAbsent(AudioPlaceLinkCandidate candidate) {
        return candidates.computeIfAbsent(LinkKey.from(candidate), ignored -> candidate);
    }

    @Override
    public synchronized void save(AudioPlaceLinkCandidate candidate) {
        candidates.put(LinkKey.from(candidate), candidate);
    }

    @Override
    public synchronized Optional<AudioPlaceLinkCandidate> find(String spotId, String placeId) {
        return Optional.ofNullable(candidates.get(new LinkKey(spotId, placeId)));
    }

    @Override
    public synchronized List<AudioPlaceLinkCandidate> findBySpotId(String spotId) {
        return candidates.values().stream()
                .filter(candidate -> candidate.spotId().equals(spotId))
                .toList();
    }

    private record LinkKey(String spotId, String placeId) {

        static LinkKey from(AudioPlaceLinkCandidate candidate) {
            return new LinkKey(candidate.spotId(), candidate.placeId());
        }
    }
}
