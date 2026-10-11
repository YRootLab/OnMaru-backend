package com.yrootlab.onmaru.admin.auth;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

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

    @Override
    public void changeStatus(UUID adminId, AdminAccountStatus status, Instant changedAt) {
        var current = findById(adminId).orElseThrow(() -> new IllegalArgumentException("admin account not found"));
        Instant boundary = current.tokensValidAfter();
        if (status != AdminAccountStatus.ACTIVE) {
            Instant nextSecond = changedAt.truncatedTo(ChronoUnit.SECONDS).plusSeconds(1);
            if (nextSecond.isAfter(boundary)) {
                boundary = nextSecond;
            }
        }
        replace(new AdminAccount(
                current.id(), current.email(), current.nickname(), current.role(), current.passwordHash(), status, boundary));
    }

    public void replace(AdminAccount account) {
        accounts.put(account.email(), account);
    }
}
