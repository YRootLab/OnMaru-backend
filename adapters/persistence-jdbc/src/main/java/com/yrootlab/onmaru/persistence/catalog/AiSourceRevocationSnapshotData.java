package com.yrootlab.onmaru.persistence.catalog;

import java.util.UUID;

public record AiSourceRevocationSnapshotData(
        UUID snapshotId, long watermark, UUID activeRevisionId, long publicPlaceCount) { }
