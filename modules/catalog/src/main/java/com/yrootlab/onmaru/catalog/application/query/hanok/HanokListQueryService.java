package com.yrootlab.onmaru.catalog.application.query.hanok;

import java.text.Normalizer;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class HanokListQueryService {

    private static final String SCHEMA_VERSION = "1.2";
    private static final String CURSOR_PREFIX = "r1.hanoks.cursor.";
    private static final Instant CURSOR_REFERENCE_NOW = Instant.parse("2026-09-14T08:00:00Z");
    private static final Duration CURSOR_TTL = Duration.ofDays(90);
    private static final Set<HanokListCategory> HANOK_RELATED_CATEGORIES = Set.of(
            HanokListCategory.HANOK,
            HanokListCategory.HANOK_STAY,
            HanokListCategory.HANOK_CAFE,
            HanokListCategory.HANOK_EXPERIENCE,
            HanokListCategory.TRADITIONAL_MARKET,
            HanokListCategory.CULTURE_ART,
            HanokListCategory.TRADITIONAL_FOOD,
            HanokListCategory.LOCAL_SCENE);

    private final HanokListStore store;
    private final HanokSavedStateLookup savedStateLookup;

    public HanokListQueryService(HanokListStore store, HanokSavedStateLookup savedStateLookup) {
        this.store = store;
        this.savedStateLookup = savedStateLookup;
    }

    public HanokListPage list(HanokListQuery query) {
        var cursor = decodeCursor(query.cursor());
        var normalizedKeyword = normalize(query.keyword());
        var filtered = store.findPublishedSnapshot().stream()
                .filter(projection -> projection.status() == HanokListStatus.PUBLIC)
                .filter(projection -> matchesKeyword(projection, normalizedKeyword))
                .filter(projection -> query.regionCode() == null || query.regionCode().equals(projection.regionCode()))
                .filter(projection -> query.category() == null || query.category() == projection.category())
                .filter(projection -> !query.hasImage() || projection.thumbnailUrl() != null)
                .sorted(order())
                .filter(projection -> cursor == null || isAfterCursor(projection, cursor))
                .toList();
        var limited = filtered.stream().limit(Math.max(query.limit(), 0) + 1L).toList();
        boolean hasMore = limited.size() > query.limit();
        List<HanokListProjection> pageItems = hasMore ? limited.subList(0, query.limit()) : limited;
        var cards = pageItems.stream()
                .map(projection -> new HanokCard(
                        projection.placeId(),
                        projection.name(),
                        projection.category(),
                        projection.regionName(),
                        projection.thumbnailUrl(),
                        projection.summary(),
                        projection.tags(),
                        savedStateLookup.savedBy(query.memberId(), projection.placeId())))
                .toList();
        var nextCursor = hasMore ? encodeCursor(pageItems.getLast()) : null;
        return new HanokListPage(SCHEMA_VERSION, cards, nextCursor, hasMore);
    }

    private Comparator<HanokListProjection> order() {
        return Comparator.comparing(HanokListProjection::publishedAt).reversed()
                .thenComparing(HanokListProjection::placeId);
    }

    private boolean matchesKeyword(HanokListProjection projection, String normalizedKeyword) {
        if (normalizedKeyword == null) {
            return true;
        }
        if (normalizedKeyword.equals("한옥") && HANOK_RELATED_CATEGORIES.contains(projection.category())) {
            return true;
        }
        return normalize(projection.name()).contains(normalizedKeyword)
                || normalize(projection.summary()).contains(normalizedKeyword)
                || projection.tags().stream()
                .map(this::normalize)
                .anyMatch(tag -> tag != null && tag.contains(normalizedKeyword));
    }

    private boolean isAfterCursor(HanokListProjection projection, DecodedCursor cursor) {
        int publishedComparison = projection.publishedAt().compareTo(cursor.publishedAt());
        if (publishedComparison < 0) {
            return true;
        }
        return publishedComparison == 0 && projection.placeId().compareTo(cursor.placeId()) > 0;
    }

    private String encodeCursor(HanokListProjection projection) {
        return CURSOR_PREFIX + projection.publishedAt() + "." + projection.placeId();
    }

    private DecodedCursor decodeCursor(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return null;
        }
        if (!cursor.startsWith(CURSOR_PREFIX)) {
            throw new HanokCursorInvalidException();
        }
        var payload = cursor.substring(CURSOR_PREFIX.length());
        var separator = payload.lastIndexOf('.');
        if (separator <= 0 || separator == payload.length() - 1) {
            throw new HanokCursorInvalidException();
        }
        try {
            var publishedAt = Instant.parse(payload.substring(0, separator));
            if (publishedAt.plus(CURSOR_TTL).isBefore(CURSOR_REFERENCE_NOW)) {
                throw new HanokCursorExpiredException();
            }
            return new DecodedCursor(publishedAt, payload.substring(separator + 1));
        } catch (HanokCursorExpiredException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new HanokCursorInvalidException();
        }
    }

    private String normalize(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return Normalizer.normalize(value.trim(), Normalizer.Form.NFC).toLowerCase(Locale.ROOT);
    }

    private record DecodedCursor(Instant publishedAt, String placeId) {
    }
}
