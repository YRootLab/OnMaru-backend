package com.yrootlab.onmaru.audio.sync;

import com.yrootlab.onmaru.catalog.application.publication.DatasetPublicationService;
import com.yrootlab.onmaru.catalog.application.publication.PublicationCommand;
import com.yrootlab.onmaru.catalog.application.publication.PublicationMode;
import com.yrootlab.onmaru.catalog.application.publication.SourceWatermark;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.HashSet;

public final class OdiiRevisionSyncService {

    private static final int MISSING_OBSERVATION_THRESHOLD = 2;

    private final AudioRevisionStore store;
    private final OdiiPageSource source;
    private final OdiiSourceMapper mapper;
    private final Clock clock;
    private final List<String> keywords;
    private final OdiiCurationPolicy curationPolicy;
    private final DatasetPublicationService publisher;
    private final OdiiSyncObserver observer;

    public OdiiRevisionSyncService(
            AudioRevisionStore store,
            OdiiPageSource source,
            OdiiSourceMapper mapper,
            Clock clock
    ) {
        this(store, source, mapper, List.of("한옥"), new OdiiCurationPolicy(), clock, OdiiSyncObserver.NOOP);
    }

    public OdiiRevisionSyncService(
            AudioRevisionStore store,
            OdiiPageSource source,
            OdiiSourceMapper mapper,
            Clock clock,
            OdiiSyncObserver observer
    ) {
        this(store, source, mapper, List.of("한옥"), new OdiiCurationPolicy(), clock, observer);
    }

    public OdiiRevisionSyncService(
            AudioRevisionStore store,
            OdiiPageSource source,
            OdiiSourceMapper mapper,
            List<String> keywords,
            Clock clock,
            OdiiSyncObserver observer
    ) {
        this(store, source, mapper, keywords, new OdiiCurationPolicy(), clock, observer);
    }

    public OdiiRevisionSyncService(
            AudioRevisionStore store,
            OdiiPageSource source,
            OdiiSourceMapper mapper,
            List<String> keywords,
            OdiiCurationPolicy curationPolicy,
            Clock clock,
            OdiiSyncObserver observer
    ) {
        this.store = store;
        this.source = source;
        this.mapper = mapper;
        this.clock = clock;
        this.keywords = List.copyOf(keywords);
        this.curationPolicy = curationPolicy;
        this.observer = observer == null ? OdiiSyncObserver.NOOP : observer;
        this.publisher = new DatasetPublicationService(store, clock);
    }

    public OdiiSyncResult sync(OdiiSyncCommand command) {
        return sync(command, OdiiSyncObserver.NOOP);
    }

    /** Per-invocation observer keeps concurrent run diagnostics isolated. */
    public OdiiSyncResult sync(OdiiSyncCommand command, OdiiSyncObserver runObserver) {
        OdiiSyncObserver observer = new OdiiSyncObserver() {
            @Override public void started(String dataset, java.util.UUID revision) {
                OdiiRevisionSyncService.this.observer.started(dataset, revision);
                runObserver.started(dataset, revision);
            }
            @Override public void phaseFailed(String dataset, java.util.UUID revision, String phase, String code) {
                runObserver.phaseFailed(dataset, revision, phase, code);
                OdiiRevisionSyncService.this.observer.phaseFailed(dataset, revision, phase, code);
            }
            @Override public void staged(String dataset, java.util.UUID revision, long count) {
                runObserver.staged(dataset, revision, count);
                OdiiRevisionSyncService.this.observer.staged(dataset, revision, count);
            }
            @Override public void stageCompleted(String dataset, java.util.UUID revision, long tombstones) {
                runObserver.stageCompleted(dataset, revision, tombstones);
                OdiiRevisionSyncService.this.observer.stageCompleted(dataset, revision, tombstones);
            }
            @Override public void fetched(String dataset, java.util.UUID revision, long count) {
                runObserver.fetched(dataset, revision, count);
                OdiiRevisionSyncService.this.observer.fetched(dataset, revision, count);
            }
            @Override public void mapped(String dataset, java.util.UUID revision, long count) {
                runObserver.mapped(dataset, revision, count);
                OdiiRevisionSyncService.this.observer.mapped(dataset, revision, count);
            }
            @Override public void completed(String dataset, OdiiSyncResult result) {
                runObserver.completed(dataset, result);
                OdiiRevisionSyncService.this.observer.completed(dataset, result);
            }
            @Override public void published(String dataset, java.util.UUID revision, OdiiSyncResult result) {
                runObserver.published(dataset, revision, result);
                OdiiRevisionSyncService.this.observer.published(dataset, revision, result);
            }
        };
        observer.started(command.dataset(), command.expectedActiveRevisionId());
        AudioRevisionStage stage;
        try {
            stage = store.openStage(command.dataset(), command.expectedActiveRevisionId(), clock.instant());
        } catch (RuntimeException exception) {
            observer.phaseFailed(command.dataset(), command.expectedActiveRevisionId(), "STAGE", "STAGE_OPEN_FAILED");
            throw exception;
        }
        Instant latestModifiedAt = null;
        String latestExternalId = null;
        var mappedStories = new java.util.ArrayList<OdiiStoryVersion>();
        var seenStoryIds = new HashSet<String>();
        try {
            for (String language : command.languages()) {
                if (source instanceof OdiiFullCollectionPageSource fullSource) {
                    int pageNumber = 1;
                    while (true) {
                        OdiiSourcePage page = fullSource.fetchFull(language, pageNumber);
                        var processed = processPage(
                                stage.revisionId(), page, null, seenStoryIds, mappedStories, latestModifiedAt,
                                latestExternalId, command.dataset(), observer);
                        latestModifiedAt = processed.latestModifiedAt();
                        latestExternalId = processed.latestExternalId();
                        if (page.lastPage()) {
                            break;
                        }
                        pageNumber++;
                    }
                } else {
                    for (String keyword : keywords) {
                        int pageNumber = 1;
                        while (true) {
                            OdiiSourcePage page = source.fetch(language, keyword, pageNumber);
                            var processed = processPage(
                                    stage.revisionId(), page, keyword, seenStoryIds, mappedStories, latestModifiedAt,
                                    latestExternalId, command.dataset(), observer);
                            latestModifiedAt = processed.latestModifiedAt();
                            latestExternalId = processed.latestExternalId();
                            if (page.lastPage()) {
                                break;
                            }
                            pageNumber++;
                        }
                    }
                }
            }
        } catch (OdiiSourceException exception) {
            observer.phaseFailed(command.dataset(), stage.revisionId(), "FETCH", "SOURCE_FAILED");
            store.failStage(stage.revisionId(), "SOURCE_FAILED");
            var result = OdiiSyncResult.sourceFailed(stage.revisionId());
            observer.completed(command.dataset(), result);
            return result;
        } catch (OdiiMappingException exception) {
            observer.phaseFailed(command.dataset(), stage.revisionId(), "MAP", "MAPPING_FAILED");
            store.failStage(stage.revisionId(), "MAPPING_FAILED");
            var result = OdiiSyncResult.sourceFailed(stage.revisionId());
            observer.completed(command.dataset(), result);
            return result;
        } catch (RuntimeException exception) {
            observer.phaseFailed(command.dataset(), stage.revisionId(), "STAGE", "STAGING_FAILED");
            store.failStage(stage.revisionId(), "STAGING_FAILED");
            throw exception;
        }

        AudioStageCompletion completion;
        long stagedItemCount;
        try {
            completion = store.completeStage(
                    stage.revisionId(), MISSING_OBSERVATION_THRESHOLD, command.emptyFullSyncReviewed());
            observer.stageCompleted(command.dataset(), stage.revisionId(), completion.tombstoneCount());
            stagedItemCount = store.stagedItemCount(stage.revisionId());
            observer.staged(command.dataset(), stage.revisionId(), stagedItemCount);
        } catch (RuntimeException exception) {
            observer.phaseFailed(command.dataset(), stage.revisionId(), "STAGE", "STAGE_COMPLETION_FAILED");
            store.failStage(stage.revisionId(), "STAGE_COMPLETION_FAILED");
            throw exception;
        }
        var watermark = new SourceWatermark(
                latestModifiedAt == null ? null : latestModifiedAt.toString(),
                latestExternalId,
                clock.instant()
        );
        com.yrootlab.onmaru.catalog.application.publication.PublicationResult publication;
        try {
            publication = publisher.publish(new PublicationCommand(
                    command.lease(), stage.revisionId(), command.expectedActiveRevisionId(),
                    PublicationMode.FULL, watermark, completion.tombstoneCount()));
        } catch (RuntimeException exception) {
            observer.phaseFailed(command.dataset(), stage.revisionId(), "PUBLISH", "PUBLISH_FAILED");
            throw exception;
        }
        var effectiveRevisionId = publication.status()
                == com.yrootlab.onmaru.catalog.application.publication.PublicationStatus.PUBLISHED
                ? store.activeRevision(command.dataset())
                : stage.revisionId();
        var tagQuality = OdiiContentTagQualitySummary.from(mappedStories);
        var result = OdiiSyncResult.publication(
                effectiveRevisionId,
                stagedItemCount,
                completion.tombstoneCount(),
                publication.status(),
                tagQuality
        );
        observer.completed(command.dataset(), result);
        observer.published(command.dataset(), effectiveRevisionId, result);
        return result;
    }

    private ProcessedPage processPage(
            java.util.UUID revisionId,
            OdiiSourcePage page,
            String keyword,
            HashSet<String> seenStoryIds,
            java.util.List<OdiiStoryVersion> mappedStories,
            Instant latestModifiedAt,
            String latestExternalId,
            String dataset,
            OdiiSyncObserver observer
    ) {
        observer.fetched(dataset, revisionId, page.stories().size());
        var mapped = page.stories().stream()
                .filter(story -> curationPolicy.decide(story, keyword).status()
                        == OdiiCurationDecision.Status.INCLUDED)
                .filter(story -> seenStoryIds.add(story.stlid()))
                .map(mapper::map)
                .peek(ignored -> observer.mapped(dataset, revisionId, 1))
                .toList();
        store.stage(revisionId, mapped);
        for (OdiiMappedStory story : mapped) {
            mappedStories.add(story.story());
            Instant modifiedAt = story.story().sourceModifiedAt();
            if (latestModifiedAt == null || modifiedAt.isAfter(latestModifiedAt)) {
                latestModifiedAt = modifiedAt;
                latestExternalId = story.story().identity().stlid();
            }
        }
        return new ProcessedPage(latestModifiedAt, latestExternalId);
    }

    private record ProcessedPage(Instant latestModifiedAt, String latestExternalId) {
    }
}
