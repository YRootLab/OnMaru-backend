package com.yrootlab.onmaru.audio.sync;

import com.yrootlab.onmaru.catalog.application.publication.PublicationStore;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface AudioRevisionStore extends PublicationStore {

    AudioRevisionStage openStage(String dataset, UUID expectedBaseRevisionId, Instant observedAt);

    void stage(UUID revisionId, List<OdiiMappedStory> stories);

    AudioStageCompletion completeStage(UUID revisionId, int missingObservationThreshold, boolean emptyFullSyncReviewed);

    void failStage(UUID revisionId, String failureCode);

    long stagedItemCount(UUID revisionId);

    UUID activeRevision(String dataset);

    AudioRevisionSnapshot activeSnapshot();
}
