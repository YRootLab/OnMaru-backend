package com.yrootlab.onmaru.community.moderation;

import com.yrootlab.onmaru.community.query.MutableVisitReviewStore;
import com.yrootlab.onmaru.community.query.VisitReviewProjection;
import com.yrootlab.onmaru.community.query.VisitReviewStatus;

import java.time.Clock;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

public final class VisitReviewModerationService {

    private final MutableVisitReviewStore reviewStore;
    private final ReviewReportStore reportStore;
    private final ReviewReportIdGenerator reportIdGenerator;
    private final ReviewReportIdGenerator actionIdGenerator;
    private final Clock clock;

    public VisitReviewModerationService(
            MutableVisitReviewStore reviewStore,
            ReviewReportStore reportStore,
            ReviewReportIdGenerator reportIdGenerator,
            ReviewReportIdGenerator actionIdGenerator,
            Clock clock) {
        this.reviewStore = reviewStore;
        this.reportStore = reportStore;
        this.reportIdGenerator = reportIdGenerator;
        this.actionIdGenerator = actionIdGenerator;
        this.clock = clock;
    }

    public ReviewReportReceipt report(UUID reporterMemberId, UUID reviewId, CreateReviewReportCommand command) {
        return reportStore.executeAtomically(() -> reportAtomically(reporterMemberId, reviewId, command));
    }

    private ReviewReportReceipt reportAtomically(
            UUID reporterMemberId,
            UUID reviewId,
            CreateReviewReportCommand command) {
        if (command == null || command.reason() == null) {
            throw new ReviewReportInvalidException();
        }
        var review = publishedReview(reviewId);
        if (review.authorMemberId().equals(reporterMemberId)) {
            throw new SelfVisitReviewReportException();
        }
        var report = reportStore.saveOrFindOpen(new ReviewReport(
                reportIdGenerator.generate(),
                reviewId,
                reporterMemberId,
                command.reason(),
                normalizeDetail(command.detail()),
                ReviewReportStatus.OPEN,
                clock.instant()));
        return ReviewReportReceipt.from(report);
    }

    public ModerationAction moderate(
            UUID reviewId,
            String actorRef,
            VisitReviewStatus nextStatus,
            ModerationReason reason) {
        return reportStore.executeAtomically(() -> {
            ModerationAction action = transition(
                    reviewId,
                    ModerationActorType.OPERATOR,
                    actorRef,
                    nextStatus,
                    reason);
            reportStore.closeOpenReports(
                    reviewId,
                    nextStatus == VisitReviewStatus.PUBLISHED
                            ? ReviewReportStatus.DISMISSED
                            : ReviewReportStatus.RESOLVED);
            return action;
        });
    }

    public ModerationAction hideHighRiskPii(UUID reviewId, String detectorRef) {
        return reportStore.executeAtomically(() -> transition(
                reviewId,
                ModerationActorType.SYSTEM,
                detectorRef,
                VisitReviewStatus.HIDDEN,
                ModerationReason.PII_HIGH_RISK));
    }

    private ModerationAction transition(
            UUID reviewId,
            ModerationActorType actorType,
            String actorRef,
            VisitReviewStatus nextStatus,
            ModerationReason reason) {
        if (actorRef == null || actorRef.isBlank() || nextStatus == null || reason == null) {
            throw new ModerationTransitionInvalidException();
        }
        var previousStatus = new AtomicReference<VisitReviewStatus>();
        var updated = reviewStore.update(reviewId, current -> {
            if (current.status() == nextStatus
                    && !isSameStatusDisposition(actorType, nextStatus, reason)) {
                throw new ModerationTransitionInvalidException();
            }
            previousStatus.set(current.status());
            return new VisitReviewProjection(
                    current.id(),
                    current.placeId(),
                    current.placeName(),
                    current.regionCode(),
                    current.lat(),
                    current.lng(),
                    current.text(),
                    current.mood(),
                    current.score(),
                    current.tags(),
                    current.createdAt(),
                    current.authorMemberId(),
                    current.likedMemberIds(),
                    nextStatus);
        }).orElseThrow(VisitReviewReportNotFoundException::new);
        var action = new ModerationAction(
                actionIdGenerator.generate(),
                reviewId,
                actorType,
                actorRef,
                previousStatus.get(),
                updated.status(),
                reason,
                clock.instant());
        reportStore.addAudit(action);
        return action;
    }

    private boolean isSameStatusDisposition(
            ModerationActorType actorType,
            VisitReviewStatus nextStatus,
            ModerationReason reason) {
        if (actorType != ModerationActorType.OPERATOR) {
            return false;
        }
        return (nextStatus == VisitReviewStatus.PUBLISHED && reason == ModerationReason.FALSE_POSITIVE)
                || (nextStatus == VisitReviewStatus.HIDDEN && reason != ModerationReason.FALSE_POSITIVE);
    }

    public List<ReviewReport> openReports() {
        return reportStore.openReports();
    }

    public List<ModerationAction> auditLog() {
        return reportStore.auditLog();
    }

    private VisitReviewProjection publishedReview(UUID reviewId) {
        return reviewStore.findSnapshot().stream()
                .filter(review -> review.id().equals(reviewId))
                .filter(review -> review.status() == VisitReviewStatus.PUBLISHED)
                .findFirst()
                .orElseThrow(VisitReviewReportNotFoundException::new);
    }

    private String normalizeDetail(String detail) {
        if (detail == null || detail.isBlank()) {
            return null;
        }
        var trimmed = detail.trim();
        if (trimmed.codePointCount(0, trimmed.length()) > 500) {
            throw new ReviewReportInvalidException();
        }
        return trimmed;
    }

}
