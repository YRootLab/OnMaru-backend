package com.yrootlab.onmaru.admin.auth;

import java.util.Optional;

public interface AdminAccountStore {

    Optional<AdminAccount> findByEmail(String normalizedEmail);

    Optional<AdminAccount> findById(java.util.UUID id);

    void recordLogin(AdminAccount account);
}
