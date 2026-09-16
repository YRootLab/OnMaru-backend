package com.yrootlab.onmaru.journey.saved.list;

import com.yrootlab.onmaru.journey.saved.place.SavedResourceType;

import java.util.List;
import java.util.UUID;

public interface SavedResourceRecordSource {
    List<SavedResourceRecord> records(UUID memberId, SavedResourceType resourceType);
}
