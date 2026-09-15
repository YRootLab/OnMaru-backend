package com.yrootlab.onmaru.audio.sync;

import com.yrootlab.onmaru.catalog.application.publication.DatasetPublicationService;
import com.yrootlab.onmaru.catalog.application.publication.PublicationCommand;
import com.yrootlab.onmaru.catalog.application.publication.PublicationMode;
import com.yrootlab.onmaru.catalog.application.publication.SourceWatermark;

import java.time.Clock;
import java.time.Instant;

public final class OdiiRevisionSyncService {

    private static final int MISSING_OBSERVATION_THRESHOLD = 2;

    private final AudioRevisionStore store;
    private final OdiiPageSource source;
    private final OdiiSourceMapper mapper;
    private final Clock clock;
    private final DatasetPublicationService publisher;

    public OdiiRevisionSyncService(
            AudioRevisionStore store,
            OdiiPageSource source,
            OdiiSourceMapper mapper,
            Clock clock
    ) {
        this.store = store;
        this.source = source;
        this.mapper = mapper;
        this.clock = clock;
        this.publisher = new DatasetPublicationService(store, clock);
    }

    public OdiiSyncResult sync(OdiiSyncCommand command) {
        AudioRevisionStage stage = store.openStage(
                command.dataset(), command.expectedActiveRevisionId(), clock.instant());
        Instant latestModifiedAt = null;
        String latestExternalId = null;
        try {
            for (String language : command.languages()) {
                int pageNumber = 1;
                while (true) {
                    OdiiSourcePage page = source.fetch(language, pageNumber);
                    var mapped = page.stories().stream().map(mapper::map).toList();
                    store.stage(stage.revisionId(), mapped);
                    for (OdiiMappedStory story : mapped) {
                        Instant modifiedAt = story.story().sourceModifiedAt();
                        if (latestModifiedAt == null || modifiedAt.isAfter(latestModifiedAt)) {
                            latestModifiedAt = modifiedAt;
                            latestExternalId = story.story().identity().stlid();
                        }
                    }
                    if (page.lastPage()) {
                        break;
                    }
                    pageNumber++;
                }
            }
        } catch (OdiiSourceException | OdiiMappingException exception) {
            store.failStage(stage.revisionId(), "SOURCE_FAILED");
            return OdiiSyncResult.sourceFailed(stage.revisionId());
        }

        AudioStageCompletion completion = store.completeStage(
                stage.revisionId(), MISSING_OBSERVATION_THRESHOLD, command.emptyFullSyncReviewed());
        var watermark = new SourceWatermark(
                latestModifiedAt == null ? null : latestModifiedAt.toString(),
                latestExternalId,
                clock.instant()
        );
        var publication = publisher.publish(new PublicationCommand(
                command.lease(),
                stage.revisionId(),
                command.expectedActiveRevisionId(),
                PublicationMode.FULL,
                watermark,
                completion.tombstoneCount()
        ));
        return OdiiSyncResult.publication(
                stage.revisionId(),
                store.stagedItemCount(stage.revisionId()),
                completion.tombstoneCount(),
                publication.status()
        );
    }
}
