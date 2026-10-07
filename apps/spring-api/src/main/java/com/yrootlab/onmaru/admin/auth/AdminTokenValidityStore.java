package com.yrootlab.onmaru.admin.auth;

import java.util.Optional;
import java.util.UUID;

public interface AdminTokenValidityStore {

    Optional<AdminTokenValidity> findByAdminId(UUID adminId);
}
