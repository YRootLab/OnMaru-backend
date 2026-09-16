package com.yrootlab.onmaru.audio.query;

import java.util.Optional;
import java.util.UUID;

@FunctionalInterface
public interface OdiiPlaceLinkResolver {

    String approvedPlaceId(String spotId, Optional<UUID> memberId);
}
