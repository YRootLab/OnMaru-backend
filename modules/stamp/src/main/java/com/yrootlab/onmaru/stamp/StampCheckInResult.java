package com.yrootlab.onmaru.stamp;

import java.util.List;

public record StampCheckInResult(
        StampCheckIn checkIn,
        List<StampAwardSummary> newAwards,
        StampBookSummary summary) {
    public StampCheckInResult {
        newAwards = List.copyOf(newAwards);
    }
}
