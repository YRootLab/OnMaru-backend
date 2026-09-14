package com.yrootlab.onmaru.catalog.editorial;

import com.yrootlab.onmaru.catalog.application.query.hanok.HanokCard;
import com.yrootlab.onmaru.catalog.application.query.hanok.HanokListProjection;
import com.yrootlab.onmaru.catalog.application.query.hanok.HanokListStatus;
import com.yrootlab.onmaru.catalog.application.query.hanok.HanokListStore;
import com.yrootlab.onmaru.catalog.application.query.hanok.HanokSavedStateLookup;

import java.time.YearMonth;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

public final class MonthlyHanokEditionService {

    private static final String SCHEMA_VERSION = "1.2";

    private final MonthlyHanokEditionStore store;
    private final HanokListStore hanokListStore;
    private final HanokSavedStateLookup savedStateLookup;

    public MonthlyHanokEditionService(
            MonthlyHanokEditionStore store,
            HanokListStore hanokListStore,
            HanokSavedStateLookup savedStateLookup) {
        this.store = store;
        this.hanokListStore = hanokListStore;
        this.savedStateLookup = savedStateLookup;
    }

    public void publish(MonthlyHanokEditionDraft draft) {
        var publicPlaces = publicPlacesById();
        for (var placement : draft.placements()) {
            if (!publicPlaces.containsKey(placement.placeId())) {
                throw new MonthlyHanokPlacementNotPublicException();
            }
        }
        store.publish(draft);
    }

    public Optional<MonthlyHanokEdition> find(YearMonth month, Optional<UUID> memberId) {
        var publicPlaces = publicPlacesById();
        return Optional.of(store.find(month)
                .map(draft -> toEdition(draft, publicPlaces, memberId))
                .orElseGet(() -> new MonthlyHanokEdition(SCHEMA_VERSION, month.toString(), "", "", List.of())));
    }

    private MonthlyHanokEdition toEdition(
            MonthlyHanokEditionDraft draft,
            java.util.Map<String, HanokListProjection> publicPlaces,
            Optional<UUID> memberId) {
        var placements = draft.placements().stream()
                .map(placement -> new MonthlyHanokPlacement(
                        placement.slot(),
                        placement.reason(),
                        toCard(publicPlaces.get(placement.placeId()), memberId)))
                .toList();
        return new MonthlyHanokEdition(
                SCHEMA_VERSION,
                draft.month().toString(),
                draft.title(),
                draft.subtitle(),
                placements);
    }

    private HanokCard toCard(HanokListProjection projection, Optional<UUID> memberId) {
        return new HanokCard(
                projection.placeId(),
                projection.name(),
                projection.category(),
                projection.regionName(),
                projection.thumbnailUrl(),
                projection.summary(),
                projection.tags(),
                savedStateLookup.savedBy(memberId, projection.placeId()));
    }

    private java.util.Map<String, HanokListProjection> publicPlacesById() {
        return hanokListStore.findPublishedSnapshot().stream()
                .filter(projection -> projection.status() == HanokListStatus.PUBLIC)
                .collect(Collectors.toMap(HanokListProjection::placeId, Function.identity()));
    }
}
