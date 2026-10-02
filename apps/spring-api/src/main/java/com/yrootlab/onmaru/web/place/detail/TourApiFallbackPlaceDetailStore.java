package com.yrootlab.onmaru.web.place.detail;

import com.yrootlab.onmaru.catalog.application.query.detail.ImageProjection;
import com.yrootlab.onmaru.catalog.application.query.detail.PlaceDetailStore;
import com.yrootlab.onmaru.catalog.application.query.detail.PlaceProjection;
import com.yrootlab.onmaru.persistence.catalog.JdbcPlaceDetailStore;
import com.yrootlab.onmaru.tourism.catalog.client.TourApiHttpClient;
import com.yrootlab.onmaru.tourism.catalog.client.TourApiParseResult;
import com.yrootlab.onmaru.tourism.catalog.client.TourApiUriBuilder;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

final class TourApiFallbackPlaceDetailStore implements PlaceDetailStore {
    private final PlaceDetailStore database;
    private final Function<String, Optional<com.yrootlab.onmaru.persistence.catalog.TourApiPlaceReference>> references;
    private final DetailFetcher fetcher;
    private final java.util.function.Predicate<String> storedHanokDetail;
    private final TourApiUriBuilder uris;

    TourApiFallbackPlaceDetailStore(JdbcPlaceDetailStore database, TourApiHttpClient client, TourApiUriBuilder uris) {
        this(database, database::findTourApiReference, database::hasStoredHanokDetail, client::get, uris);
    }

    TourApiFallbackPlaceDetailStore(
            PlaceDetailStore database,
            Function<String, Optional<com.yrootlab.onmaru.persistence.catalog.TourApiPlaceReference>> references,
            java.util.function.Predicate<String> storedHanokDetail,
            DetailFetcher fetcher,
            TourApiUriBuilder uris) {
        this.database = database;
        this.references = references;
        this.storedHanokDetail = storedHanokDetail;
        this.fetcher = fetcher;
        this.uris = uris;
    }

    @Override public Optional<PlaceProjection> findByPlaceId(String placeId) {
        return enrich(placeId, database.findByPlaceId(placeId));
    }

    @Override public Optional<PlaceProjection> findHanokByPlaceId(String placeId) {
        return enrich(placeId, database.findHanokByPlaceId(placeId));
    }

    private Optional<PlaceProjection> enrich(String placeId, Optional<PlaceProjection> found) {
        if (found.isEmpty()) return found;
        if (storedHanokDetail.test(placeId)) return found;
        try {
            var reference = references.apply(placeId).orElse(null);
            if (reference == null || reference.contentTypeId() == null) return found;
            var base = found.orElseThrow();
            var common = fetch("detailCommon2", uris.detailCommon(reference.contentId()));
            var intro = fetch("detailIntro2", uris.detailIntro(reference.contentId(), reference.contentTypeId()));
            var info = fetch("detailInfo2", uris.detailInfo(reference.contentId(), reference.contentTypeId()));
            String description = text(common, "overview", base.description());
            String image = text(common, "firstimage", null);
            var images = image == null ? base.images() : List.of(new ImageProjection(image, base.name()));
            var highlights = new ArrayList<>(base.highlights());
            for (String field : List.of("usetime", "restdate", "parking", "expguide", "reservation")) {
                String value = text(intro, field, null); if (value != null) highlights.add(value);
            }
            if (info != null && info.page() != null) info.page().items().forEach(item -> {
                String value = item.fields().path("infotext").asText(""); if (!value.isBlank()) highlights.add(value);
            });
            return Optional.of(new PlaceProjection(base.placeId(), base.name(), base.category(), base.region(),
                    base.address(), base.coordinates(), images, description, List.copyOf(highlights),
                    base.contentTags(), base.odiiLinkedResourceId(), base.status(), base.ambiguousMapping()));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return found;
        } catch (Exception ignored) {
            return found;
        }
    }

    private TourApiParseResult fetch(String operation, java.net.URI uri) throws Exception { return fetcher.fetch(operation, uri); }
    private String text(TourApiParseResult result, String field, String fallback) {
        if (result == null || result.page() == null || result.page().items().isEmpty()) return fallback;
        String value = result.page().items().getFirst().fields().path(field).asText("");
        return value.isBlank() ? fallback : value;
    }

    @FunctionalInterface
    interface DetailFetcher {
        TourApiParseResult fetch(String operation, java.net.URI uri) throws Exception;
    }
}
