package com.yrootlab.onmaru.admin.users;

import java.util.ArrayList;
import java.util.List;

public final class InMemoryAdminMemberStore implements AdminMemberStore {

    private final List<AdminMember> members = new ArrayList<>();

    @Override
    public synchronized List<AdminMember> find(String status, int limit) {
        return members.stream()
                .filter(member -> status == null || status.equalsIgnoreCase(member.status()))
                .limit(limit)
                .toList();
    }

    public synchronized void add(AdminMember member) {
        members.add(member);
    }
}
