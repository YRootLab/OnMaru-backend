package com.yrootlab.onmaru.community.query;

import java.util.List;
import com.yrootlab.onmaru.catalog.application.pagination.AdminCursor;
import com.yrootlab.onmaru.catalog.application.pagination.AdminPage;

public interface VisitReviewStore {

    List<VisitReviewProjection> findSnapshot();

    default AdminPage<VisitReviewProjection> findAdminPage(VisitReviewStatus status, int limit, AdminCursor cursor) {
        return findAdminPage(status, null, limit, cursor);
    }

    AdminPage<VisitReviewProjection> findAdminPage(VisitReviewStatus status, String query, int limit, AdminCursor cursor);
}
