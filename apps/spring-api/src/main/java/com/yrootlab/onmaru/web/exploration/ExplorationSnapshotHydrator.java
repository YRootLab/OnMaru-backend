package com.yrootlab.onmaru.web.exploration;

import com.yrootlab.onmaru.catalog.application.query.detail.CanonicalPlaceDetail;
import com.yrootlab.onmaru.catalog.application.query.detail.PlaceDetailQueryService;
import com.yrootlab.onmaru.catalog.application.query.detail.PlaceDetailUnavailableException;
import com.yrootlab.onmaru.journey.exploration.ExplorationRunOutcome;
import com.yrootlab.onmaru.journey.exploration.ExplorationRunStatus;
import com.yrootlab.onmaru.journey.exploration.ExplorationSnapshot;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Component
final class ExplorationSnapshotHydrator {

    private static final String JEONJU_REGION = "kr-45-jeonju";
    private static final String JEONJU_PLACE = "p-jeonju-hanok-village";

    private final PlaceDetailQueryService placeDetailQueryService;

    ExplorationSnapshotHydrator(PlaceDetailQueryService placeDetailQueryService) {
        this.placeDetailQueryService = placeDetailQueryService;
    }

    HydratedSnapshot hydrate(ExplorationSnapshot snapshot) {
        var base = HydratedSnapshot.empty(snapshot);
        if (!hasCommittedBoard(snapshot)) {
            return base;
        }
        var placeId = candidatePlaceId(snapshot.regionCode());
        if (placeId == null) {
            return base;
        }
        Optional<CanonicalPlaceDetail> place;
        try {
            place = placeDetailQueryService.findCanonicalPlace(placeId, Optional.empty());
        } catch (PlaceDetailUnavailableException exception) {
            return new HydratedSnapshot(null, List.of(placeRef(placeId)), publicExecution(snapshot));
        }
        if (place.isEmpty()) {
            return new HydratedSnapshot(null, List.of(placeRef(placeId)), publicExecution(snapshot));
        }
        return new HydratedSnapshot(board(place.get()), List.of(), publicExecution(snapshot));
    }

    private boolean hasCommittedBoard(ExplorationSnapshot snapshot) {
        var run = snapshot.run();
        return run != null
                && run.status() == ExplorationRunStatus.COMPLETED
                && run.outcome() == ExplorationRunOutcome.INITIAL_BOARD;
    }

    private String candidatePlaceId(String regionCode) {
        if (JEONJU_REGION.equals(regionCode)) {
            return JEONJU_PLACE;
        }
        return null;
    }

    private Object board(CanonicalPlaceDetail place) {
        var regionRef = regionRef(place.region().regionCode());
        var placeRef = placeRef(place.placeId());
        return orderedMap(
                "title", place.region().name().startsWith("전북") ? "전주 한옥 산책" : place.name() + " 산책",
                "querySummary", place.region().name() + "에서 한옥 문화를 천천히 둘러보는 여정",
                "regionRef", regionRef,
                "candidates", List.of(orderedMap(
                        "placeRef", placeRef,
                        "reason", "현재 공개 catalog에서 복구 가능한 한옥 장소입니다.",
                        "evidenceRefs", List.of("evidence-catalog-current-" + place.placeId()),
                        "relationRefs", List.of("relation-region-" + place.placeId()),
                        "constraintChecks", List.of(orderedMap(
                                "key", "REGION",
                                "status", "SATISFIED",
                                "label", place.region().name(),
                                "evidenceRefs", List.of("evidence-catalog-current-" + place.placeId()))))),
                "legs", List.of(),
                "resources", List.of(placeResource(place), orderedMap(
                        "ref", regionRef,
                        "title", place.region().name())),
                "relations", List.of(orderedMap(
                        "id", "relation-region-" + place.placeId(),
                        "sourceRef", placeRef,
                        "targetRef", regionRef,
                        "type", "LOCATED_IN",
                        "label", place.region().name(),
                        "evidenceRefs", List.of("evidence-catalog-current-" + place.placeId()))),
                "evidence", List.of(orderedMap(
                        "id", "evidence-catalog-current-" + place.placeId(),
                        "kind", "PROVIDER_FIELD",
                        "sourceName", "OnMaru Catalog",
                        "sourceUrl", null,
                        "sourceRevision", "catalog-current",
                        "summary", "현재 공개 catalog snapshot에서 복구한 장소와 지역 연결",
                        "asOf", null)));
    }

    private Object placeResource(CanonicalPlaceDetail place) {
        var image = place.images().stream()
                .findFirst()
                .map(value -> orderedMap(
                        "url", value.url(),
                        "alt", value.alt(),
                        "sourceName", "OnMaru Catalog"))
                .orElse(null);
        var location = place.coordinates() == null
                ? null
                : orderedMap(
                        "latitude", place.coordinates().lat(),
                        "longitude", place.coordinates().lng(),
                        "accuracy", "APPROXIMATE");
        return orderedMap(
                "ref", placeRef(place.placeId()),
                "title", place.name(),
                "category", place.category(),
                "regionRef", regionRef(place.region().regionCode()),
                "summary", place.description(),
                "image", image,
                "location", location,
                "sourceRefs", List.of("catalog-current"),
                "unavailableFields", unavailableFields(image, location));
    }

    private List<String> unavailableFields(Object image, Object location) {
        var fields = new java.util.ArrayList<String>();
        if (image == null) {
            fields.add("image");
        }
        if (location == null) {
            fields.add("location");
        }
        return List.copyOf(fields);
    }

    private static Map<String, Object> publicExecution(ExplorationSnapshot snapshot) {
        return Map.of(
                "dataMode", "PUBLIC",
                "engine", snapshot.run().engine(),
                "rankingVersion", "baseline-v1",
                "datasetRevision", "catalog-current");
    }

    private static Map<String, Object> pendingExecution(ExplorationSnapshot snapshot) {
        return Map.of(
                "dataMode", "LIVE",
                "engine", snapshot.run().engine(),
                "rankingVersion", "pending",
                "datasetRevision", "pending");
    }

    private static Map<String, Object> placeRef(String placeId) {
        return Map.of("type", "PLACE", "id", placeId);
    }

    private static Map<String, Object> regionRef(String regionCode) {
        return Map.of("type", "REGION", "id", regionCode);
    }

    private static Map<String, Object> orderedMap(Object... entries) {
        Map<String, Object> values = new LinkedHashMap<>();
        for (int index = 0; index < entries.length; index += 2) {
            values.put((String) entries[index], entries[index + 1]);
        }
        return values;
    }

    record HydratedSnapshot(Object board, List<Object> unavailableRefs, Map<String, Object> execution) {

        static HydratedSnapshot empty(ExplorationSnapshot snapshot) {
            return new HydratedSnapshot(null, List.of(), pendingExecution(snapshot));
        }
    }
}
