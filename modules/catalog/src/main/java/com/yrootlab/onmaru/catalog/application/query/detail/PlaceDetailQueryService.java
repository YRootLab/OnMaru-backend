package com.yrootlab.onmaru.catalog.application.query.detail;

import com.yrootlab.onmaru.catalog.application.tags.ContentTagExtractor;
import com.yrootlab.onmaru.catalog.application.tags.ContentTagPipeline;
import com.yrootlab.onmaru.catalog.application.tags.ContentTagSource;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public final class PlaceDetailQueryService {

    private static final String SCHEMA_VERSION = "1.2";
    private static final int MAX_CONTENT_TAGS = 7;

    private final PlaceDetailStore store;
    private final SavedPlaceStateLookup savedPlaceStateLookup;
    private final ContentTagPipeline contentTagPipeline;

    public PlaceDetailQueryService(PlaceDetailStore store, SavedPlaceStateLookup savedPlaceStateLookup) {
        this(store, savedPlaceStateLookup, ContentTagPipeline.defaultPipeline());
    }

    public PlaceDetailQueryService(
            PlaceDetailStore store,
            SavedPlaceStateLookup savedPlaceStateLookup,
            ContentTagExtractor contentTagExtractor) {
        this(store, savedPlaceStateLookup, ContentTagPipeline.of(contentTagExtractor));
    }

    public PlaceDetailQueryService(
            PlaceDetailStore store,
            SavedPlaceStateLookup savedPlaceStateLookup,
            ContentTagPipeline contentTagPipeline) {
        this.store = store;
        this.savedPlaceStateLookup = savedPlaceStateLookup;
        this.contentTagPipeline = contentTagPipeline;
    }

    public Optional<CanonicalPlaceDetail> findCanonicalPlace(String placeId, Optional<UUID> memberId) {
        return findPublicProjection(placeId)
                .map(projection -> new CanonicalPlaceDetail(
                        SCHEMA_VERSION,
                        projection.placeId(),
                        projection.name(),
                        projection.category(),
                        projection.region(),
                        projection.address(),
                        projection.coordinates(),
                        projection.images(),
                        projection.description(),
                        contentTags(projection),
                        savedPlaceStateLookup.savedBy(memberId, projection.placeId())));
    }

    public Optional<HanokDetail> findHanok(String placeId, Optional<UUID> memberId) {
        return findPublicProjection(placeId)
                .filter(projection -> "한옥".equals(projection.category()))
                .map(projection -> {
                    boolean savedByMe = savedPlaceStateLookup.savedBy(memberId, projection.placeId());
                    var mapCard = linkedCard(projection, savedByMe);
                    var odiiCard = projection.odiiLinkedResourceId() == null ? null : linkedCard(projection, savedByMe);
                    return new HanokDetail(
                            SCHEMA_VERSION,
                            projection.placeId(),
                            projection.name(),
                            HanokCategory.HANOK,
                            projection.region(),
                            projection.address(),
                            projection.coordinates(),
                            projection.images(),
                            projection.description(),
                            projection.highlights(),
                            contentTags(projection),
                            savedByMe,
                            mapCard,
                            odiiCard);
                });
    }

    private Optional<PlaceProjection> findPublicProjection(String placeId) {
        return store.findByPlaceId(placeId)
                .filter(projection -> projection.status() == PlaceProjectionStatus.PUBLIC)
                .filter(projection -> !projection.ambiguousMapping());
    }

    private LinkedPlaceCard linkedCard(PlaceProjection projection, boolean savedByMe) {
        return new LinkedPlaceCard(
                projection.placeId(),
                projection.name(),
                projection.category(),
                projection.region().name(),
                projection.images().stream().findFirst().map(ImageProjection::url).orElse(null),
                savedByMe);
    }

    private List<String> contentTags(PlaceProjection projection) {
        if (!projection.contentTags().isEmpty()) {
            return projection.contentTags();
        }
        return contentTagPipeline.generate(ContentTagSource.of(
                projection.name(),
                projection.category(),
                projection.description(),
                projection.highlights()), MAX_CONTENT_TAGS).publicLabels();
    }
}
