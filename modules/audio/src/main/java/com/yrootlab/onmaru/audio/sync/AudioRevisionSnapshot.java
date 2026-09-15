package com.yrootlab.onmaru.audio.sync;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class AudioRevisionSnapshot {

    private final Map<OdiiSpotIdentity, OdiiSpotVersion> spots;
    private final Map<OdiiStoryIdentity, OdiiStoryVersion> stories;

    private AudioRevisionSnapshot(
            Map<OdiiSpotIdentity, OdiiSpotVersion> spots,
            Map<OdiiStoryIdentity, OdiiStoryVersion> stories
    ) {
        this.spots = new LinkedHashMap<>(spots);
        this.stories = new LinkedHashMap<>(stories);
    }

    public static AudioRevisionSnapshot empty() {
        return new AudioRevisionSnapshot(Map.of(), Map.of());
    }

    public static AudioRevisionSnapshot from(List<OdiiMappedStory> mappedStories) {
        var snapshot = empty();
        mappedStories.forEach(snapshot::put);
        return snapshot;
    }

    public AudioRevisionSnapshot copy() {
        return new AudioRevisionSnapshot(spots, stories);
    }

    public List<OdiiSpotVersion> spots() {
        return List.copyOf(spots.values());
    }

    public List<OdiiStoryVersion> stories() {
        return List.copyOf(stories.values());
    }

    OdiiSpotVersion spot(OdiiSpotIdentity identity) {
        return spots.get(identity);
    }

    OdiiStoryVersion story(OdiiStoryIdentity identity) {
        return stories.get(identity);
    }

    void put(OdiiMappedStory mapped) {
        if (!mapped.story().spotIdentity().equals(mapped.spot().identity())) {
            throw new IllegalArgumentException("story and spot identity must match");
        }
        spots.put(mapped.spot().identity(), mapped.spot());
        stories.put(mapped.story().identity(), mapped.story());
    }

    void putSpot(OdiiSpotVersion spot) {
        spots.put(spot.identity(), spot);
    }

    void putStory(OdiiStoryVersion story) {
        stories.put(story.identity(), story);
    }
}
