package com.yrootlab.onmaru.audio.sync;

import com.yrootlab.onmaru.catalog.application.publication.PublicationPlan;
import com.yrootlab.onmaru.catalog.application.publication.PublicationStatus;
import com.yrootlab.onmaru.catalog.application.publication.SourceWatermark;
import com.yrootlab.onmaru.catalog.application.publication.StageValidation;
import com.yrootlab.onmaru.catalog.application.sync.SyncRunLease;

import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public final class InMemoryAudioRevisionStore implements AudioRevisionStore {

    private final String dataset;
    private final String leaseOwner;
    private final int leaseGeneration;
    private final Map<UUID, StageState> stages = new LinkedHashMap<>();
    private final Map<OdiiSpotIdentity, Integer> missingSpots = new HashMap<>();
    private final Map<OdiiStoryIdentity, Integer> missingStories = new HashMap<>();
    private UUID activeRevision;
    private AudioRevisionSnapshot activeSnapshot;
    private SourceWatermark watermark;

    public InMemoryAudioRevisionStore(
            String dataset,
            UUID activeRevision,
            AudioRevisionSnapshot activeSnapshot,
            SourceWatermark watermark,
            String leaseOwner,
            int leaseGeneration
    ) {
        this.dataset = dataset;
        this.activeRevision = activeRevision;
        this.activeSnapshot = activeSnapshot.copy();
        this.watermark = watermark;
        this.leaseOwner = leaseOwner;
        this.leaseGeneration = leaseGeneration;
    }

    @Override
    public synchronized AudioRevisionStage openStage(
            String dataset,
            UUID expectedBaseRevisionId,
            Instant observedAt
    ) {
        if (!this.dataset.equals(dataset)) {
            throw new IllegalArgumentException("unknown dataset: " + dataset);
        }
        UUID revisionId = UUID.randomUUID();
        stages.put(revisionId, new StageState(
                activeSnapshot.copy(),
                new HashMap<>(missingSpots),
                new HashMap<>(missingStories)
        ));
        return new AudioRevisionStage(revisionId, expectedBaseRevisionId);
    }

    @Override
    public synchronized void stage(UUID revisionId, List<OdiiMappedStory> stories) {
        StageState stage = requiredStage(revisionId);
        if (stage.validation != null) {
            throw new IllegalStateException("stage is already terminal");
        }
        for (OdiiMappedStory mapped : stories) {
            stage.snapshot.put(mapped);
            stage.observedSpots.add(mapped.spot().identity());
            stage.observedStories.add(mapped.story().identity());
        }
    }

    @Override
    public synchronized AudioStageCompletion completeStage(
            UUID revisionId,
            int missingObservationThreshold,
            boolean emptyFullSyncReviewed
    ) {
        if (missingObservationThreshold < 1) {
            throw new IllegalArgumentException("missingObservationThreshold must be positive");
        }
        StageState stage = requiredStage(revisionId);
        long tombstones = tombstoneMissingStories(stage, missingObservationThreshold)
                + tombstoneMissingSpots(stage, missingObservationThreshold);
        long rowCount = stage.observedStories.size();
        stage.validation = StageValidation.ready(rowCount, emptyFullSyncReviewed);
        return new AudioStageCompletion(rowCount, tombstones);
    }

    @Override
    public synchronized void failStage(UUID revisionId, String failureCode) {
        requiredStage(revisionId).validation = StageValidation.incomplete(failureCode);
    }

    @Override
    public synchronized long stagedItemCount(UUID revisionId) {
        StageState stage = requiredStage(revisionId);
        return stage.snapshot.spots().size() + stage.snapshot.stories().size();
    }

    @Override
    public synchronized Optional<StageValidation> stageValidation(UUID revisionId) {
        StageState stage = stages.get(revisionId);
        return stage == null ? Optional.empty() : Optional.ofNullable(stage.validation);
    }

    @Override
    public synchronized PublicationStatus publishIfLeaseAndBaseRevisionMatch(
            SyncRunLease lease,
            PublicationPlan plan,
            Instant publishedAt
    ) {
        if (!dataset.equals(lease.dataset())
                || !leaseOwner.equals(lease.ownerToken())
                || leaseGeneration != lease.generation()) {
            return PublicationStatus.LEASE_LOST;
        }
        if (!activeRevision.equals(plan.expectedActiveRevisionId())) {
            return PublicationStatus.ACTIVE_REVISION_CHANGED;
        }
        StageState stage = stages.get(plan.revisionId());
        if (stage == null || stage.validation == null || !stage.validation.ready()) {
            return PublicationStatus.STAGE_INCOMPLETE;
        }
        activeRevision = plan.revisionId();
        activeSnapshot = stage.snapshot.copy();
        watermark = plan.watermark();
        missingSpots.clear();
        missingSpots.putAll(stage.missingSpots);
        missingStories.clear();
        missingStories.putAll(stage.missingStories);
        return PublicationStatus.PUBLISHED;
    }

    @Override
    public synchronized UUID activeRevision(String dataset) {
        if (!this.dataset.equals(dataset)) {
            throw new IllegalArgumentException("unknown dataset: " + dataset);
        }
        return activeRevision;
    }

    @Override
    public synchronized AudioRevisionSnapshot activeSnapshot() {
        return activeSnapshot.copy();
    }

    @Override
    public synchronized ActiveAudioRevision activePublishedRevision(String dataset) {
        if (!this.dataset.equals(dataset)) {
            throw new IllegalArgumentException("unknown dataset: " + dataset);
        }
        return new ActiveAudioRevision(activeRevision, activeSnapshot);
    }

    public synchronized SourceWatermark watermark() {
        return watermark;
    }

    private long tombstoneMissingStories(StageState stage, int threshold) {
        long tombstones = 0;
        for (OdiiStoryVersion story : activeSnapshot.stories()) {
            if (stage.observedStories.contains(story.identity())) {
                stage.missingStories.remove(story.identity());
            } else if (story.status() == AudioStatus.ACTIVE) {
                int count = stage.missingStories.merge(story.identity(), 1, Integer::sum);
                if (count >= threshold) {
                    stage.snapshot.putStory(deleted(story));
                    tombstones++;
                }
            }
        }
        return tombstones;
    }

    private long tombstoneMissingSpots(StageState stage, int threshold) {
        long tombstones = 0;
        for (OdiiSpotVersion spot : activeSnapshot.spots()) {
            if (stage.observedSpots.contains(spot.identity())) {
                stage.missingSpots.remove(spot.identity());
            } else if (spot.status() == AudioStatus.ACTIVE) {
                int count = stage.missingSpots.merge(spot.identity(), 1, Integer::sum);
                if (count >= threshold) {
                    stage.snapshot.putSpot(deleted(spot));
                    tombstones++;
                }
            }
        }
        return tombstones;
    }

    private OdiiStoryVersion deleted(OdiiStoryVersion story) {
        return new OdiiStoryVersion(
                story.identity(), story.spotIdentity(), story.title(), null, TranscriptProvenance.MISSING,
                null, null, null, story.sourceModifiedAt(), AudioStatus.DELETED,
                "deleted:" + story.contentHash());
    }

    private OdiiSpotVersion deleted(OdiiSpotVersion spot) {
        return new OdiiSpotVersion(
                spot.identity(), spot.title(), spot.longitude(), spot.latitude(), spot.sourceModifiedAt(),
                AudioStatus.DELETED, "deleted:" + spot.contentHash());
    }

    private StageState requiredStage(UUID revisionId) {
        StageState stage = stages.get(revisionId);
        if (stage == null) {
            throw new IllegalArgumentException("unknown revision: " + revisionId);
        }
        return stage;
    }

    private static final class StageState {
        private final AudioRevisionSnapshot snapshot;
        private final Set<OdiiSpotIdentity> observedSpots = new HashSet<>();
        private final Set<OdiiStoryIdentity> observedStories = new HashSet<>();
        private final Map<OdiiSpotIdentity, Integer> missingSpots;
        private final Map<OdiiStoryIdentity, Integer> missingStories;
        private StageValidation validation;

        private StageState(
                AudioRevisionSnapshot snapshot,
                Map<OdiiSpotIdentity, Integer> missingSpots,
                Map<OdiiStoryIdentity, Integer> missingStories
        ) {
            this.snapshot = snapshot;
            this.missingSpots = missingSpots;
            this.missingStories = missingStories;
        }
    }
}
