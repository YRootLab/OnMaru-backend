package com.yrootlab.onmaru.catalog.application.publication;

import java.util.UUID;

public record PublicationPlan(
        UUID revisionId,
        UUID expectedActiveRevisionId,
        SourceWatermark watermark,
        long tombstoneCount
) {
}
