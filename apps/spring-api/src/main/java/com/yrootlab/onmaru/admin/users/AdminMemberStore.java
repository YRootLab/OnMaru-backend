package com.yrootlab.onmaru.admin.users;

import java.util.List;
import com.yrootlab.onmaru.catalog.application.pagination.AdminCursor;
import com.yrootlab.onmaru.catalog.application.pagination.AdminPage;

public interface AdminMemberStore {

    List<AdminMember> find(String status, int limit);

    AdminPage<AdminMember> findPage(String status, int limit, AdminCursor cursor);
}
