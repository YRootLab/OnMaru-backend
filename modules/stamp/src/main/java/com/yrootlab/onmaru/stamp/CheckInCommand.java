package com.yrootlab.onmaru.stamp;

public record CheckInCommand(String placeId, double latitude, double longitude, double accuracyMeters) {
}
