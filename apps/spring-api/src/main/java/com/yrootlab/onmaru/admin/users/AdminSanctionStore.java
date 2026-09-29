package com.yrootlab.onmaru.admin.users;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface AdminSanctionStore {

    List<AdminSanction> findByMember(UUID memberId);

    AdminSanction create(UUID memberId, UUID adminId, String reason, Instant startsAt, Instant endsAt);

    boolean revoke(UUID sanctionId, UUID adminId, Instant revokedAt);
}
