package com.yrootlab.onmaru.admin.curation;

import java.util.List;
import java.util.UUID;
import com.yrootlab.onmaru.catalog.application.pagination.AdminCursor;
import com.yrootlab.onmaru.catalog.application.pagination.AdminPage;

public interface AdminCurationStore {
    List<AdminCuration> find(String category, Boolean included, int limit);
    AdminPage<AdminCuration> findPage(String category, Boolean included, int limit, AdminCursor cursor);
    AdminCuration upsert(UUID placeId, String category, boolean included, List<String> badges, UUID adminId);
}
