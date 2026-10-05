package com.yrootlab.onmaru.admin.users;

import java.util.ArrayList;
import java.util.List;
import com.yrootlab.onmaru.catalog.application.pagination.AdminCursor;
import com.yrootlab.onmaru.catalog.application.pagination.AdminPage;
import java.util.Comparator;

public final class InMemoryAdminMemberStore implements AdminMemberStore {

    private final List<AdminMember> members = new ArrayList<>();

    @Override
    public synchronized List<AdminMember> find(String status, int limit) {
        return members.stream()
                .filter(member -> status == null || status.equalsIgnoreCase(member.status()))
                .limit(limit)
                .toList();
    }

    @Override
    public synchronized AdminPage<AdminMember> findPage(String status, int limit, AdminCursor cursor) {
        var matching = members.stream()
                .filter(member -> status == null || status.equalsIgnoreCase(member.status()))
                .sorted(Comparator.comparing(AdminMember::createdAt).reversed().thenComparing(AdminMember::id, Comparator.reverseOrder()))
                .toList();
        var filtered = matching.stream()
                .filter(member -> cursor == null || member.createdAt().isBefore(cursor.timestamp())
                        || member.createdAt().equals(cursor.timestamp()) && member.id().compareTo(cursor.id()) < 0)
                .limit(limit + 1L)
                .toList();
        boolean hasNext = filtered.size() > limit;
        long totalCount = cursor != null && cursor.totalCount() != null ? cursor.totalCount() : matching.size();
        return new AdminPage<>(filtered.subList(0, Math.min(limit, filtered.size())), hasNext, totalCount);
    }

    public synchronized void add(AdminMember member) {
        members.add(member);
    }
}
