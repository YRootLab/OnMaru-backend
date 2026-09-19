package com.yrootlab.onmaru.audio.query;

import java.util.Optional;
import java.util.UUID;

@FunctionalInterface
public interface OdiiSavedStateLookup {

    boolean savedBy(Optional<UUID> memberId, String storyId);
}
