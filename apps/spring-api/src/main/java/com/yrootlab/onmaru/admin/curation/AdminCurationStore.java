package com.yrootlab.onmaru.admin.curation;

import java.util.List;
import java.util.UUID;

public interface AdminCurationStore {
    List<AdminCuration> find(String category, Boolean included, int limit);
    AdminCuration upsert(UUID placeId, String category, boolean included, List<String> badges, UUID adminId);
}
