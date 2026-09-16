package com.yrootlab.onmaru.audio.placelink;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public final class AudioPlaceLinkService implements ApprovedAudioPlaceLinkQuery {

    private final AudioPlaceLinkStore store;
    private final CanonicalPlaceLinkLookup canonicalPlaceLookup;
    private final Clock clock;

    public AudioPlaceLinkService(
            AudioPlaceLinkStore store,
            CanonicalPlaceLinkLookup canonicalPlaceLookup,
            Clock clock) {
        this.store = Objects.requireNonNull(store, "store");
        this.canonicalPlaceLookup = Objects.requireNonNull(canonicalPlaceLookup, "canonicalPlaceLookup");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public AudioPlaceLinkCandidate submitCandidate(AudioPlaceLinkCandidateCommand command) {
        Objects.requireNonNull(command, "command");
        return store.saveIfAbsent(AudioPlaceLinkCandidate.pending(command));
    }

    public synchronized AudioPlaceLinkCandidate review(
            String spotId,
            String placeId,
            AudioPlaceLinkReviewDecision decision) {
        Objects.requireNonNull(decision, "decision");
        AudioPlaceLinkCandidate candidate = store.find(spotId, placeId)
                .orElseThrow(AudioPlaceLinkCandidateNotFoundException::new);
        Instant reviewedAt = clock.instant();
        if (decision == AudioPlaceLinkReviewDecision.APPROVE) {
            canonicalPlaceLookup.findPublicPlace(placeId, Optional.empty())
                    .orElseThrow(AudioPlaceLinkTargetUnavailableException::new);
            rejectOtherCandidates(spotId, placeId, reviewedAt);
            candidate = candidate.reviewed(AudioPlaceLinkReviewStatus.APPROVED, reviewedAt);
        } else {
            candidate = candidate.reviewed(AudioPlaceLinkReviewStatus.REJECTED, reviewedAt);
        }
        store.save(candidate);
        return candidate;
    }

    public List<AudioPlaceLinkCandidate> candidates(String spotId) {
        return store.findBySpotId(spotId);
    }

    @Override
    public Optional<ApprovedAudioPlaceLink> findApprovedPlace(
            String spotId,
            Optional<UUID> memberId) {
        List<AudioPlaceLinkCandidate> approved = store.findBySpotId(spotId).stream()
                .filter(candidate -> candidate.reviewStatus() == AudioPlaceLinkReviewStatus.APPROVED)
                .toList();
        if (approved.size() != 1) {
            return Optional.empty();
        }
        AudioPlaceLinkCandidate link = approved.getFirst();
        return canonicalPlaceLookup.findPublicPlace(link.placeId(), memberId)
                .map(place -> new ApprovedAudioPlaceLink(
                        link.spotId(),
                        link.matchMethod(),
                        link.confidence(),
                        link.reviewedAt(),
                        place));
    }

    private void rejectOtherCandidates(String spotId, String approvedPlaceId, Instant reviewedAt) {
        store.findBySpotId(spotId).stream()
                .filter(candidate -> !candidate.placeId().equals(approvedPlaceId))
                .map(candidate -> candidate.reviewed(AudioPlaceLinkReviewStatus.REJECTED, reviewedAt))
                .forEach(store::save);
    }
}
