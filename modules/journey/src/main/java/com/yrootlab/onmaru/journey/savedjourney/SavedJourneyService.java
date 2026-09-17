package com.yrootlab.onmaru.journey.savedjourney;

import com.yrootlab.onmaru.journey.actions.ResourceRef;
import com.yrootlab.onmaru.journey.exploration.ExplorationActiveRunException;
import com.yrootlab.onmaru.journey.exploration.ExplorationRunOutcome;
import com.yrootlab.onmaru.journey.exploration.ExplorationRunStatus;
import com.yrootlab.onmaru.journey.exploration.ExplorationService;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

public final class SavedJourneyService {

    private static final int LIMIT = 100;

    private final SavedJourneyStore store;
    private final ExplorationService explorations;
    private final Clock clock;
    private final ResourceAvailability availability;

    public SavedJourneyService(
            SavedJourneyStore store,
            ExplorationService explorations,
            Clock clock,
            ResourceAvailability availability) {
        this.store = store;
        this.explorations = explorations;
        this.clock = clock;
        this.availability = availability;
    }

    public SavedJourneySaveResult save(CreateSavedJourneyCommand command) {
        validate(command);
        var exploration = explorations.get(command.actor(), command.explorationId());
        var run = exploration.run();
        if (run != null && (run.status() == ExplorationRunStatus.QUEUED || run.status() == ExplorationRunStatus.RUNNING)) {
            throw new ExplorationActiveRunException();
        }
        if (exploration.stateVersion() != command.baseVersion()) {
            throw new SavedJourneyInputInvalidException("baseVersion");
        }
        if (run == null || run.status() != ExplorationRunStatus.COMPLETED || run.outcome() != ExplorationRunOutcome.INITIAL_BOARD) {
            throw new SavedJourneyInputInvalidException("explorationId");
        }
        var actions = explorations.actionState(command.actor(), command.explorationId());
        var snapshot = SavedJourneySnapshot.seed(
                exploration.explorationId(),
                exploration.stateVersion(),
                command.title().trim(),
                exploration.regionCode(),
                actions.orderedRefs().isEmpty()
                        ? List.of(new ResourceRef("PLACE", "p-jeonju-hanok-village"))
                        : actions.orderedRefs(),
                actions.pinnedRefs(),
                actions.excludedRefs(),
                exploration.updatedAt());
        return store.save(command.memberId(), snapshot, clock.instant(), LIMIT);
    }

    public SavedJourneyPage list(UUID memberId, int limit, String cursor) {
        if (memberId == null || limit < 1 || limit > 50) {
            throw new SavedJourneyInputInvalidException("limit");
        }
        var offset = decodeCursor(cursor);
        var items = store.list(memberId);
        if (offset > items.size()) {
            throw new SavedJourneyInputInvalidException("cursor");
        }
        var toIndex = Math.min(items.size(), offset + limit);
        var selected = items.subList(offset, toIndex);
        var hasMore = toIndex < items.size();
        return new SavedJourneyPage(selected, hasMore ? encodeCursor(toIndex) : null, hasMore);
    }

    public SavedJourney get(UUID memberId, UUID savedJourneyId) {
        return store.find(memberId, savedJourneyId).orElseThrow(SavedJourneyNotFoundException::new);
    }

    public void delete(UUID memberId, UUID savedJourneyId) {
        store.delete(memberId, savedJourneyId);
    }

    public ResumeSavedJourneyResult resume(UUID memberId, UUID savedJourneyId) {
        var saved = get(memberId, savedJourneyId);
        var snapshot = saved.snapshot();
        var unavailable = new ArrayList<ResourceRef>();
        var ordered = new ArrayList<ResourceRef>();
        for (var ref : snapshot.orderedRefs()) {
            if (availability.isPublic(ref)) {
                ordered.add(ref);
            } else {
                unavailable.add(ref);
            }
        }
        var pinned = snapshot.pinnedRefs().stream().filter(ordered::contains).toList();
        var excluded = snapshot.excludedRefs().stream().filter(availability::isPublic).toList();
        return new ResumeSavedJourneyResult(
                new ResumeSavedJourneyResult.ResumedExploration(
                        UUID.randomUUID(),
                        ordered.isEmpty() ? 0 : 1,
                        snapshot.regionCode(),
                        ordered,
                        pinned,
                        excluded),
                unavailable);
    }

    private void validate(CreateSavedJourneyCommand command) {
        if (command == null || command.memberId() == null || command.actor() == null || command.explorationId() == null) {
            throw new SavedJourneyInputInvalidException("body");
        }
        if (command.baseVersion() < 0) {
            throw new SavedJourneyInputInvalidException("baseVersion");
        }
        if (command.title() == null || command.title().isBlank() || command.title().length() > 80) {
            throw new SavedJourneyInputInvalidException("title");
        }
    }

    private int decodeCursor(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return 0;
        }
        try {
            var decoded = new String(Base64.getUrlDecoder().decode(cursor), java.nio.charset.StandardCharsets.UTF_8);
            if (!decoded.startsWith("offset:")) {
                throw new IllegalArgumentException("scope");
            }
            var offset = Integer.parseInt(decoded.substring("offset:".length()));
            if (offset < 0) {
                throw new IllegalArgumentException("negative");
            }
            return offset;
        } catch (IllegalArgumentException exception) {
            throw new SavedJourneyInputInvalidException("cursor");
        }
    }

    private String encodeCursor(int offset) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(("offset:" + offset).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
}
