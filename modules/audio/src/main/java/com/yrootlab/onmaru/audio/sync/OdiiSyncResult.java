package com.yrootlab.onmaru.audio.sync;

import com.yrootlab.onmaru.catalog.application.publication.PublicationStatus;

import java.util.UUID;

public record OdiiSyncResult(
        OdiiSyncStatus status,
        UUID stagedRevisionId,
        long itemCount,
        long tombstoneCount,
        OdiiContentTagQualitySummary contentTagQuality
) {

    public OdiiSyncResult {
        contentTagQuality = contentTagQuality == null
                ? OdiiContentTagQualitySummary.empty()
                : contentTagQuality;
    }

    static OdiiSyncResult sourceFailed(UUID revisionId) {
        return new OdiiSyncResult(
                OdiiSyncStatus.SOURCE_FAILED,
                revisionId,
                0,
                0,
                OdiiContentTagQualitySummary.empty());
    }

    static OdiiSyncResult publication(
            UUID revisionId,
            long itemCount,
            long tombstoneCount,
            PublicationStatus status,
            OdiiContentTagQualitySummary contentTagQuality
    ) {
        return new OdiiSyncResult(switch (status) {
            case PUBLISHED -> OdiiSyncStatus.PUBLISHED;
            case STAGE_INCOMPLETE -> OdiiSyncStatus.STAGE_INCOMPLETE;
            case EMPTY_FULL_SYNC_REQUIRES_REVIEW -> OdiiSyncStatus.EMPTY_FULL_SYNC_REQUIRES_REVIEW;
            case LEASE_LOST -> OdiiSyncStatus.LEASE_LOST;
            case ACTIVE_REVISION_CHANGED -> OdiiSyncStatus.ACTIVE_REVISION_CHANGED;
        }, revisionId, itemCount, tombstoneCount, contentTagQuality);
    }
}
