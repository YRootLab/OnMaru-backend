package com.yrootlab.onmaru.catalog.screenhanok;

import com.yrootlab.onmaru.catalog.application.query.hanok.HanokListProjection;
import com.yrootlab.onmaru.catalog.application.query.hanok.HanokListStore;
import com.yrootlab.onmaru.catalog.application.query.hanok.HanokSavedStateLookup;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

public final class ScreenHanokQueryService {

    private final ScreenHanokPlacementStore placementStore;
    private final HanokListStore hanokListStore;
    private final HanokSavedStateLookup savedStateLookup;

    public ScreenHanokQueryService(
            ScreenHanokPlacementStore placementStore,
            HanokListStore hanokListStore,
            HanokSavedStateLookup savedStateLookup) {
        this.placementStore = placementStore;
        this.hanokListStore = hanokListStore;
        this.savedStateLookup = savedStateLookup;
    }

    public List<ScreenHanokEntry> list(
            Optional<String> regionName,
            Optional<ScreenHanokMediaType> mediaType,
            Optional<UUID> memberId) {
        Map<String, HanokListProjection> catalog = hanokListStore.findPublishedSnapshot().stream()
                .collect(Collectors.toMap(HanokListProjection::placeId, Function.identity(), (first, second) -> first));

        return placementStore.current().stream()
                .filter(placement -> mediaType.isEmpty() || mediaType.get() == placement.mediaType())
                .map(placement -> toEntry(placement, catalog.get(placement.placeId()), memberId))
                .flatMap(Optional::stream)
                .filter(entry -> regionName.isEmpty() || regionName.get().equals(entry.regionName()))
                .toList();
    }

    private Optional<ScreenHanokEntry> toEntry(
            ScreenHanokPlacement placement,
            HanokListProjection projection,
            Optional<UUID> memberId) {
        if (projection == null) {
            return Optional.empty();
        }
        return Optional.of(new ScreenHanokEntry(
                projection.placeId(),
                projection.name(),
                projection.regionName(),
                projection.thumbnailUrl(),
                placement.mediaType(),
                placement.workTitle(),
                placement.subtitle(),
                placement.tags(),
                placement.sourceUrl(),
                placement.sourceTitle(),
                savedStateLookup.savedBy(memberId, projection.placeId())));
    }
}
