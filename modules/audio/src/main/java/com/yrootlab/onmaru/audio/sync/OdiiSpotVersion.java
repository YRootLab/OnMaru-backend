package com.yrootlab.onmaru.audio.sync;

import java.math.BigDecimal;
import java.time.Instant;

public record OdiiSpotVersion(
        OdiiSpotIdentity identity,
        String title,
        BigDecimal longitude,
        BigDecimal latitude,
        Instant sourceModifiedAt,
        AudioStatus status,
        String contentHash
) {
}
