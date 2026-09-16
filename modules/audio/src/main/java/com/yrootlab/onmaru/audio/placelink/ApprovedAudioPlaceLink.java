package com.yrootlab.onmaru.audio.placelink;

import java.math.BigDecimal;
import java.time.Instant;

public record ApprovedAudioPlaceLink(
        String spotId,
        AudioPlaceLinkMatchMethod matchMethod,
        BigDecimal confidence,
        Instant verifiedAt,
        CanonicalPlaceLinkCard place) {
}
