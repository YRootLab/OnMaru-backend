package com.yrootlab.onmaru.admin.auth;

import java.util.Optional;
import java.time.Instant;
import java.util.UUID;

public interface AdminAccountStore {

    Optional<AdminAccount> findByEmail(String normalizedEmail);

    Optional<AdminAccount> findById(java.util.UUID id);

    void recordLogin(AdminAccount account);

    void changeStatus(UUID adminId, AdminAccountStatus status, Instant changedAt);
}
