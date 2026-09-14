package com.yrootlab.onmaru.catalog.application.publication;

public enum PublicationStatus {
    PUBLISHED,
    STAGE_INCOMPLETE,
    EMPTY_FULL_SYNC_REQUIRES_REVIEW,
    LEASE_LOST,
    ACTIVE_REVISION_CHANGED
}
