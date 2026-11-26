package com.yrootlab.onmaru.web.exploration;

import com.yrootlab.onmaru.catalog.application.query.detail.CanonicalPlaceDetail;
import com.yrootlab.onmaru.catalog.application.query.detail.PlaceDetailQueryService;
import com.yrootlab.onmaru.catalog.application.query.detail.PlaceDetailUnavailableException;
import com.yrootlab.onmaru.journey.exploration.ExplorationRunOutcome;
import com.yrootlab.onmaru.journey.exploration.ExplorationRunStatus;
import com.yrootlab.onmaru.journey.exploration.ExplorationSnapshot;
import com.yrootlab.onmaru.journey.exploration.PersistedExplorationBoard;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Component
final class ExplorationSnapshotHydrator {

    private static final int MAX_BOARD_PLACES = 5;

    private final PlaceDetailQueryService placeDetailQueryService;

    ExplorationSnapshotHydrator(PlaceDetailQueryService placeDetailQueryService) {
        this.placeDetailQueryService = placeDetailQueryService;
    }

    HydratedSnapshot hydrate(ExplorationSnapshot snapshot) {
        var base = HydratedSnapshot.empty(snapshot);
        var run = snapshot.run();
        if (run == null || run.status() != ExplorationRunStatus.COMPLETED) {
            return base;
        }
        if (run.outcome() == ExplorationRunOutcome.NO_RESULTS) {
            return new HydratedSnapshot(null, List.of(), publicExecution(snapshot));
        }
        if (!hasCommittedBoard(snapshot)) {
            return base;
        }
        PersistedExplorationBoard persisted = snapshot.board();
        if (persisted != null) {
            return hydratePersistedBoard(snapshot, persisted);
        }
        // No committed board was persisted: nothing to hydrate. Region-specific fallbacks are not
        // allowed here; the board comes only from the AI result's orderedRefs.
        return base;
    }

    private HydratedSnapshot hydratePersistedBoard(ExplorationSnapshot snapshot, PersistedExplorationBoard persisted) {
        var refs = persisted.orderedRefs().stream()
                .filter(ref -> ref != null && !ref.isBlank())
                .distinct()
                .limit(MAX_BOARD_PLACES)
                .toList();
        if (refs.isEmpty()) {
            return new HydratedSnapshot(null, List.of(), publicExecution(snapshot));
        }
        var places = new ArrayList<CanonicalPlaceDetail>();
        var unavailableRefs = new ArrayList<Object>();
        for (String placeId : refs) {
            try {
                Optional<CanonicalPlaceDetail> place = placeDetailQueryService.findCanonicalPlace(placeId, Optional.empty());
                if (place.isPresent()) {
                    places.add(place.get());
                } else {
                    unavailableRefs.add(placeRef(placeId));
                }
            } catch (PlaceDetailUnavailableException exception) {
                unavailableRefs.add(placeRef(placeId));
            }
        }
        if (places.isEmpty()) {
            return new HydratedSnapshot(null, List.copyOf(unavailableRefs), publicExecution(snapshot));
        }
        return new HydratedSnapshot(
                persistedBoard(snapshot, persisted, places),
                List.copyOf(unavailableRefs),
                publicExecution(snapshot));
    }

    private boolean hasCommittedBoard(ExplorationSnapshot snapshot) {
        var run = snapshot.run();
        return run != null
                && run.status() == ExplorationRunStatus.COMPLETED
                && (run.outcome() == ExplorationRunOutcome.INITIAL_BOARD
                || run.outcome() == ExplorationRunOutcome.PROPOSAL);
    }

    private Object persistedBoard(
            ExplorationSnapshot snapshot,
            PersistedExplorationBoard persisted,
            List<CanonicalPlaceDetail> places) {
        var firstPlace = places.getFirst();
        var primaryRegionCode = snapshot.regionCode() != null && !snapshot.regionCode().isBlank()
                ? snapshot.regionCode()
                : firstPlace.region().regionCode();
        var primaryRegionRef = regionRef(primaryRegionCode);
        var title = places.size() == 1 && firstPlace.region().name().startsWith("전북")
                ? "전주 한옥 산책"
                : firstPlace.region().name() + " 여행 코스";
        var querySummary = persisted.summaryText() != null && !persisted.summaryText().isBlank()
                ? persisted.summaryText()
                : firstPlace.region().name() + "에서 추천 장소를 둘러보는 여정";

        var candidates = new ArrayList<Object>();
        var resources = new ArrayList<Object>();
        var regions = new LinkedHashMap<String, String>();
        var relations = new ArrayList<Object>();
        var evidence = new ArrayList<Object>();

        for (CanonicalPlaceDetail place : places) {
            var placeRef = placeRef(place.placeId());
            var regionCode = place.region().regionCode();
            var regionName = place.region().name();
            var regionRef = regionRef(regionCode);
            var evidenceId = "evidence-catalog-current-" + place.placeId();
            var relationId = "relation-region-" + place.placeId();
            var reason = persisted.placeReasons().get(place.placeId());
            if (reason == null || reason.isBlank()) {
                reason = "현재 공개 catalog에서 확인된 추천 장소입니다.";
            }

            candidates.add(orderedMap(
                    "placeRef", placeRef,
                    "reason", reason,
                    "evidenceRefs", List.of(evidenceId),
                    "relationRefs", List.of(relationId),
                    "constraintChecks", List.of(orderedMap(
                            "key", "REGION",
                            "status", "SATISFIED",
                            "label", regionName,
                            "evidenceRefs", List.of(evidenceId)))));
            resources.add(placeResource(place));
            regions.putIfAbsent(regionCode, regionName);
            relations.add(orderedMap(
                    "id", relationId,
                    "sourceRef", placeRef,
                    "targetRef", regionRef,
                    "type", "LOCATED_IN",
                    "label", regionName,
                    "evidenceRefs", List.of(evidenceId)));
            evidence.add(orderedMap(
                    "id", evidenceId,
                    "kind", "PROVIDER_FIELD",
                    "sourceName", "OnMaru Catalog",
                    "sourceUrl", null,
                    "sourceRevision", "catalog-current",
                    "summary", "현재 공개 catalog snapshot에서 복구한 장소와 지역 연결",
                    "asOf", null));
        }

        regions.forEach((code, name) -> resources.add(orderedMap("ref", regionRef(code), "title", name)));
        if (!regions.containsKey(primaryRegionCode)) {
            resources.add(orderedMap("ref", primaryRegionRef, "title", primaryRegionCode));
        }

        return orderedMap(
                "title", title,
                "querySummary", querySummary,
                "regionRef", primaryRegionRef,
                "candidates", List.copyOf(candidates),
                "legs", List.of(),
                "resources", List.copyOf(resources),
                "relations", List.copyOf(relations),
                "evidence", List.copyOf(evidence));
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
                "address", place.address(),
                "image", image,
                "location", location,
                "sourceRefs", List.of("catalog-current"),
                "unavailableFields", unavailableFields(image, location));
    }

    private List<String> unavailableFields(Object image, Object location) {
        var fields = new ArrayList<String>();
        if (image == null) {
            fields.add("image");
        }
        if (location == null) {
            fields.add("location");
        }
        return List.copyOf(fields);
    }

    private static Map<String, Object> publicExecution(ExplorationSnapshot snapshot) {
        String engine = snapshot.board() != null && snapshot.board().engine() != null
                ? snapshot.board().engine()
                : snapshot.run().engine();
        String rankingVersion = "LLM".equals(engine) ? "ai-rag-v1" : "baseline-v1";
        return Map.of(
                "dataMode", "PUBLIC",
                "engine", engine,
                "rankingVersion", rankingVersion,
                "datasetRevision", "catalog-current");
    }

    private static Map<String, Object> pendingExecution(ExplorationSnapshot snapshot) {
        String engine = snapshot.run() == null ? "BASELINE" : snapshot.run().engine();
        return Map.of(
                "dataMode", "LIVE",
                "engine", engine,
                "rankingVersion", "pending",
                "datasetRevision", "pending");
    }

    private static Map<String, Object> placeRef(String placeId) {
        return Map.of("type", "PLACE", "id", placeId);
    }

    private static Map<String, Object> regionRef(String regionCode) {
        return Map.of("type", "REGION", "id", regionCode);
    }

    /**
     * Builds a board for one completed turn from the refs stored with that turn, so older turns keep their
     * cards after later runs overwrite the exploration board. Returns null when no ref resolves to a public place.
     */
    Map<String, Object> boardForTurnRefs(List<String> refs, Map<String, String> placeReasons) {
        if (refs == null || refs.isEmpty()) {
            return null;
        }
        var places = new ArrayList<CanonicalPlaceDetail>();
        for (String placeId : refs.stream().distinct().limit(MAX_BOARD_PLACES).toList()) {
            try {
                placeDetailQueryService.findCanonicalPlace(placeId, Optional.empty()).ifPresent(places::add);
            } catch (PlaceDetailUnavailableException exception) {
                // An unavailable place is skipped; the rest of the turn's cards still render.
            }
        }
        if (places.isEmpty()) {
            return null;
        }
        var reasons = placeReasons == null ? Map.<String, String>of() : placeReasons;
        var candidates = new ArrayList<Object>();
        for (CanonicalPlaceDetail place : places) {
            candidates.add(orderedMap(
                    "placeRef", placeRef(place.placeId()),
                    "reason", reasons.get(place.placeId()),
                    "evidenceRefs", List.of(),
                    "relationRefs", List.of(),
                    "constraintChecks", List.of()));
        }
        var resources = new ArrayList<Object>();
        var regionCodes = new java.util.LinkedHashSet<String>();
        for (CanonicalPlaceDetail place : places) {
            resources.add(placeResource(place));
            regionCodes.add(place.region().regionCode());
        }
        for (CanonicalPlaceDetail place : places) {
            if (regionCodes.remove(place.region().regionCode())) {
                resources.add(orderedMap(
                        "ref", regionRef(place.region().regionCode()),
                        "title", place.region().name()));
            }
        }
        return orderedMap(
                "title", "추천 장소",
                "querySummary", "",
                "regionRef", regionRef(places.get(0).region().regionCode()),
                "candidates", candidates,
                "legs", List.of(),
                "resources", resources,
                "relations", List.of(),
                "evidence", List.of());
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
