package com.yrootlab.onmaru.journey.saved.list;

import com.yrootlab.onmaru.journey.saved.place.SavedResourceType;

import java.time.Instant;
import java.util.UUID;

public record SavedResourceRecord(UUID id, SavedResourceType resourceType, String resourceId, Instant savedAt) {
}
