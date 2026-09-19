package com.yrootlab.onmaru.audio.placelink;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface AudioPlaceLinkStore {

    AudioPlaceLinkCandidate saveIfAbsent(AudioPlaceLinkCandidate candidate);

    void save(AudioPlaceLinkCandidate candidate);

    Optional<AudioPlaceLinkCandidate> find(String spotId, String placeId);

    List<AudioPlaceLinkCandidate> findBySpotId(String spotId);

    AudioPlaceLinkCandidate approveExclusive(String spotId, String placeId, Instant reviewedAt);

    AudioPlaceLinkCandidate reject(String spotId, String placeId, Instant reviewedAt);
}
