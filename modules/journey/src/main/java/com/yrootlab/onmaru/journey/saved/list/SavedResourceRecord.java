package com.yrootlab.onmaru.journey.saved.list;

import com.yrootlab.onmaru.journey.saved.place.SavedResourceType;

import java.time.Instant;

public record SavedResourceRecord(SavedResourceType resourceType, String resourceId, Instant savedAt) {
}
