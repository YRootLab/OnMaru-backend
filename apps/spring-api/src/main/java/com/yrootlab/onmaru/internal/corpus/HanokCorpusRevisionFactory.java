package com.yrootlab.onmaru.internal.corpus;

import com.yrootlab.onmaru.catalog.application.query.hanok.HanokListCategory;
import com.yrootlab.onmaru.catalog.application.query.hanok.HanokListProjection;
import com.yrootlab.onmaru.catalog.application.query.hanok.HanokListStatus;
import com.yrootlab.onmaru.catalog.application.query.hanok.HanokListStore;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** Builds a bounded, normalized Hanok-only corpus from the published catalog snapshot. */
public final class HanokCorpusRevisionFactory {

    private final HanokListStore store;

    public HanokCorpusRevisionFactory(HanokListStore store) {
        this.store = store;
    }

    public CorpusRevision current() {
        var source = store.findPublishedSnapshot().stream()
                .filter(item -> item.status() == HanokListStatus.PUBLIC)
                .filter(item -> isHanok(item.category()))
                .toList();
        var publishedAt = source.stream()
                .map(HanokListProjection::publishedAt)
                .filter(java.util.Objects::nonNull)
                .max(Instant::compareTo)
                .orElse(Instant.EPOCH);
        var revisionId = "hanok-" + publishedAt.toEpochMilli();
        var documents = new ArrayList<CorpusDocument>();
        for (var item : source) {
            var text = normalizedText(item);
            if (text.isBlank()) {
                continue;
            }
            documents.add(CorpusDocument.create(
                    revisionId,
                    "hanok:" + item.placeId(),
                    "HANOK_PLACE",
                    item.placeId(),
                    revisionId,
                    "catalog:" + item.placeId(),
                    item.category().name(),
                    item.regionName(),
                    true,
                    text));
        }
        return new CorpusRevision(revisionId, publishedAt, documents, List.of());
    }

    private static boolean isHanok(HanokListCategory category) {
        return category == HanokListCategory.HANOK
                || category == HanokListCategory.HANOK_STAY
                || category == HanokListCategory.HANOK_CAFE
                || category == HanokListCategory.HANOK_EXPERIENCE;
    }

    private static String normalizedText(HanokListProjection item) {
        var parts = new ArrayList<String>();
        add(parts, item.name());
        add(parts, item.summary());
        add(parts, item.address());
        add(parts, item.regionName());
        parts.addAll(item.tags().stream().filter(java.util.Objects::nonNull).toList());
        return String.join(" ", parts).replaceAll("\\s+", " ").trim();
    }

    private static void add(List<String> target, String value) {
        if (value != null && !value.isBlank()) {
            target.add(value.trim());
        }
    }
}
