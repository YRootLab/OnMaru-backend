package com.yrootlab.onmaru.community.moderation;

import com.yrootlab.onmaru.community.query.VisitReviewStatus;

import java.time.Instant;
import java.util.UUID;

public record ModerationAction(
        UUID actionId,
        UUID reviewId,
        ModerationActorType actorType,
        String actorRef,
        VisitReviewStatus previousStatus,
        VisitReviewStatus nextStatus,
        ModerationReason reason,
        Instant createdAt) {
}
