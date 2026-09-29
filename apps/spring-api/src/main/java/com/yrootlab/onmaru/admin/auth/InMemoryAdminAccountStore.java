package com.yrootlab.onmaru.admin.auth;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public final class InMemoryAdminAccountStore implements AdminAccountStore {

    private final Map<String, AdminAccount> accounts = new ConcurrentHashMap<>();

    public InMemoryAdminAccountStore() {
    }

    public InMemoryAdminAccountStore(AdminAccount account) {
        replace(account);
    }

    @Override
    public Optional<AdminAccount> findByEmail(String normalizedEmail) {
        return Optional.ofNullable(accounts.get(normalizedEmail));
    }

    @Override
    public Optional<AdminAccount> findById(java.util.UUID id) {
        return accounts.values().stream().filter(account -> account.id().equals(id)).findFirst();
    }

    @Override
    public void recordLogin(AdminAccount account) {
        replace(account);
    }

    public void replace(AdminAccount account) {
        accounts.put(account.email(), account);
    }
}
