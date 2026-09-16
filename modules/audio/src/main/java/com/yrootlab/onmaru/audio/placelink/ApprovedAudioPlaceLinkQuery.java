package com.yrootlab.onmaru.audio.placelink;

import java.util.Optional;
import java.util.UUID;

@FunctionalInterface
public interface ApprovedAudioPlaceLinkQuery {

    Optional<ApprovedAudioPlaceLink> findApprovedPlace(String spotId, Optional<UUID> memberId);
}
