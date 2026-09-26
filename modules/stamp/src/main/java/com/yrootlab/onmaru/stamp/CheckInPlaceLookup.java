package com.yrootlab.onmaru.stamp;

import java.util.Optional;

public interface CheckInPlaceLookup {
    Optional<VerifiedPlace> verify(String placeId, double latitude, double longitude);
}
