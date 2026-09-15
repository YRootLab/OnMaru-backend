package com.yrootlab.onmaru.audio.placelink;

import java.util.Optional;
import java.util.UUID;

@FunctionalInterface
public interface CanonicalPlaceLinkLookup {

    Optional<CanonicalPlaceLinkCard> findPublicPlace(String placeId, Optional<UUID> memberId);
}
