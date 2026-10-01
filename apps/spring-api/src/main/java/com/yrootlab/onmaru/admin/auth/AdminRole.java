package com.yrootlab.onmaru.admin.auth;

public enum AdminRole {
    ADMIN,
    EDITOR;

    public boolean canManageUsers() {
        return this == ADMIN;
    }

    public boolean canManagePipelines() {
        return this == ADMIN;
    }
}
