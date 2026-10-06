package com.yrootlab.onmaru.admin.auth;

import com.yrootlab.onmaru.persistence.admin.JdbcAdminJtiRevocationStore;

public final class AdminRevocationStoreStartupValidator {

    private final AdminJtiRevocationStore store;

    public AdminRevocationStoreStartupValidator(AdminJtiRevocationStore store) {
        this.store = store;
    }

    public void validate() {
        AdminJtiRevocationStore delegate = store instanceof ObservedAdminJtiRevocationStore observed
                ? observed.delegate()
                : store;
        if (!(delegate instanceof JdbcAdminJtiRevocationStore)) {
            throw new IllegalStateException("Production admin JTI revocation store must use JDBC");
        }
    }
}
