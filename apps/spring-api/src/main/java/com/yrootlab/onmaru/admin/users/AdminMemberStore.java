package com.yrootlab.onmaru.admin.users;

import java.util.List;

public interface AdminMemberStore {

    List<AdminMember> find(String status, int limit);
}
