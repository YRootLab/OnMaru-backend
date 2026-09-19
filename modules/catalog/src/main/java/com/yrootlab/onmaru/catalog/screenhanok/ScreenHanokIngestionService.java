package com.yrootlab.onmaru.catalog.screenhanok;

import com.yrootlab.onmaru.catalog.application.query.hanok.HanokListStore;
import com.yrootlab.onmaru.catalog.application.query.hanok.HanokListUnavailableException;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Daily batch (ADR-0010): researches every already-published hanok/traditional place for real
 * K-content appearances and republishes the screen-hanok feed atomically. A research failure, or
 * any candidate the research port could not source, never produces a partial publish — the last
 * verified snapshot is kept instead.
 */
public final class ScreenHanokIngestionService {

    private static final int RESEARCH_BATCH_SIZE = 20;

    private final HanokListStore hanokListStore;
    private final ScreenHanokResearchPort researchPort;
    private final ScreenHanokPlacementStore placementStore;
    private final Clock clock;

    public ScreenHanokIngestionService(
            HanokListStore hanokListStore,
            ScreenHanokResearchPort researchPort,
            ScreenHanokPlacementStore placementStore,
            Clock clock) {
        this.hanokListStore = hanokListStore;
        this.researchPort = researchPort;
        this.placementStore = placementStore;
        this.clock = clock;
    }

    public void sync() {
        List<ScreenHanokCandidate> candidates;
        try {
            candidates = hanokListStore.findPublishedSnapshot().stream()
                    .map(projection -> new ScreenHanokCandidate(
                            projection.placeId(),
                            projection.name(),
                            projection.regionName(),
                            projection.category().name()))
                    .toList();
        } catch (HanokListUnavailableException exception) {
            return;
        }
        if (candidates.isEmpty()) {
            return;
        }

        Instant now = Instant.now(clock);
        List<ScreenHanokPlacement> placements = new ArrayList<>();
        for (List<ScreenHanokCandidate> batch : partition(candidates, RESEARCH_BATCH_SIZE)) {
            List<ScreenHanokMatch> matches;
            try {
                matches = researchPort.research(batch);
            } catch (ScreenHanokResearchUnavailableException exception) {
                return;
            }
            matches.stream()
                    .filter(ScreenHanokMatch::hasSource)
                    .map(match -> new ScreenHanokPlacement(
                            match.placeId(),
                            match.mediaType(),
                            match.workTitle(),
                            match.subtitle(),
                            match.tags(),
                            match.sourceUrl(),
                            match.sourceTitle(),
                            now))
                    .forEach(placements::add);
        }
        placementStore.publish(placements);
    }

    private static List<List<ScreenHanokCandidate>> partition(List<ScreenHanokCandidate> source, int size) {
        List<List<ScreenHanokCandidate>> batches = new ArrayList<>();
        for (int i = 0; i < source.size(); i += size) {
            batches.add(source.subList(i, Math.min(i + size, source.size())));
        }
        return batches;
    }
}
