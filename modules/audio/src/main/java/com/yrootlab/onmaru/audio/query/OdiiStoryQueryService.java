package com.yrootlab.onmaru.audio.query;

import com.yrootlab.onmaru.audio.placelink.ApprovedAudioPlaceLinkQuery;
import com.yrootlab.onmaru.audio.sync.AudioStatus;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

public final class OdiiStoryQueryService {

    private static final String SCHEMA_VERSION = "1.2";
    private static final String FALLBACK_LANGUAGE = "ko-KR";
    private static final Pattern LANGUAGE_PATTERN = Pattern.compile("^[a-z]{2}(-[A-Z]{2})?$");
    private static final Pattern STORY_ID_PATTERN = Pattern.compile("^odii-story-[a-z0-9-]{3,80}$");
    private static final Comparator<OdiiStoryProjection> ORDER = Comparator
            .comparing(OdiiStoryProjection::publishedAt).reversed()
            .thenComparing(OdiiStoryProjection::storyId);

    private final OdiiStoryQueryStore store;
    private final OdiiSavedStateLookup savedStateLookup;
    private final ApprovedAudioPlaceLinkQuery approvedPlaceLinkQuery;
    private final OdiiPublicAudioUrlPolicy audioUrlPolicy;
    private final OdiiStoryCursorCodec cursorCodec;

    public OdiiStoryQueryService(
            OdiiStoryQueryStore store,
            OdiiSavedStateLookup savedStateLookup,
            ApprovedAudioPlaceLinkQuery approvedPlaceLinkQuery,
            OdiiPublicAudioUrlPolicy audioUrlPolicy,
            OdiiStoryCursorCodec cursorCodec) {
        this.store = store;
        this.savedStateLookup = savedStateLookup;
        this.approvedPlaceLinkQuery = approvedPlaceLinkQuery;
        this.audioUrlPolicy = audioUrlPolicy;
        this.cursorCodec = cursorCodec;
    }

    public OdiiStoryPage list(OdiiStoryQuery query) {
        validateQuery(query);
        var snapshot = store.activeSnapshot();
        var cursor = decodeCursor(query.cursor(), snapshot.revisionId(), query);
        var eligible = snapshot.stories().stream()
                .filter(this::isPublicAndPlayable)
                .filter(story -> query.category() == null || query.category().equals(story.category()))
                .filter(story -> query.regionCode() == null
                        || query.regionCode().equals(story.region().regionCode()))
                .toList();
        var selection = selectLanguage(eligible, query.language());
        var ordered = deduplicate(selection.stories()).stream()
                .sorted(ORDER)
                .filter(story -> cursor == null || isAfterCursor(story, cursor))
                .limit(query.limit() + 1L)
                .toList();
        boolean hasMore = ordered.size() > query.limit();
        var pageItems = hasMore ? ordered.subList(0, query.limit()) : ordered;
        var summaries = pageItems.stream()
                .map(story -> summary(story, query.memberId()))
                .toList();
        String nextCursor = hasMore
                ? encodeCursor(snapshot.revisionId(), query, pageItems.getLast())
                : null;
        var coverage = summaries.isEmpty()
                ? OdiiCoverageStatus.MISSING
                : selection.status() == OdiiLanguageStatus.EXACT
                ? OdiiCoverageStatus.COMPLETE
                : OdiiCoverageStatus.PARTIAL;
        return new OdiiStoryPage(
                SCHEMA_VERSION,
                coverage,
                selection.language(),
                selection.status(),
                summaries,
                nextCursor,
                hasMore);
    }

    public OdiiStoryDetail detail(String storyId, String language, Optional<UUID> memberId) {
        validateLanguage(language);
        if (storyId == null || !STORY_ID_PATTERN.matcher(storyId).matches()) {
            throw new OdiiStoryInvalidRequestException("storyId");
        }
        var candidates = store.activeSnapshot().stories().stream()
                .filter(story -> storyId.equals(story.storyId()))
                .filter(this::isPublicAndPlayable)
                .toList();
        var selection = selectLanguage(candidates, language);
        if (selection.stories().isEmpty()) {
            throw new OdiiStoryNotFoundException();
        }
        var projection = selection.stories().stream().sorted(ORDER).findFirst().orElseThrow();
        return new OdiiStoryDetail(
                SCHEMA_VERSION,
                detailCoverage(selection.status(), projection.transcriptStatus()),
                selection.language(),
                selection.status(),
                summary(projection, memberId),
                projection.audioUrl(),
                projection.transcriptStatus(),
                projection.transcript());
    }

    private void validateQuery(OdiiStoryQuery query) {
        if (query == null) {
            throw new OdiiStoryInvalidRequestException("query");
        }
        validateLanguage(query.language());
        if (query.limit() < 1 || query.limit() > 50) {
            throw new OdiiStoryInvalidRequestException("limit");
        }
        if (query.category() != null && query.category().length() > 80) {
            throw new OdiiStoryInvalidRequestException("category");
        }
        if (query.regionCode() != null && query.regionCode().length() > 32) {
            throw new OdiiStoryInvalidRequestException("regionCode");
        }
        if (query.cursor() != null && query.cursor().length() > 512) {
            throw new OdiiCursorInvalidException();
        }
    }

    private void validateLanguage(String language) {
        if (language == null || !LANGUAGE_PATTERN.matcher(language).matches()) {
            throw new OdiiStoryInvalidRequestException("language");
        }
    }

    private boolean isPublicAndPlayable(OdiiStoryProjection story) {
        return story.status() == AudioStatus.ACTIVE
                && story.spotStatus() == AudioStatus.ACTIVE
                && story.region() != null
                && story.coordinates() != null
                && audioUrlPolicy.allows(story.audioUrl());
    }

    private LanguageSelection selectLanguage(List<OdiiStoryProjection> stories, String requestedLanguage) {
        var exact = stories.stream()
                .filter(story -> requestedLanguage.equals(story.language()))
                .toList();
        if (!exact.isEmpty()) {
            return new LanguageSelection(requestedLanguage, OdiiLanguageStatus.EXACT, exact);
        }
        var fallback = stories.stream()
                .filter(story -> FALLBACK_LANGUAGE.equals(story.language()))
                .toList();
        if (!fallback.isEmpty()) {
            return new LanguageSelection(FALLBACK_LANGUAGE, OdiiLanguageStatus.FALLBACK, fallback);
        }
        return new LanguageSelection(requestedLanguage, OdiiLanguageStatus.MISSING, List.of());
    }

    private List<OdiiStoryProjection> deduplicate(List<OdiiStoryProjection> stories) {
        var byStoryId = new LinkedHashMap<String, OdiiStoryProjection>();
        stories.stream().sorted(ORDER).forEach(story -> byStoryId.putIfAbsent(story.storyId(), story));
        return List.copyOf(byStoryId.values());
    }

    private OdiiStorySummary summary(OdiiStoryProjection story, Optional<UUID> memberId) {
        var effectiveMemberId = memberId == null ? Optional.<UUID>empty() : memberId;
        return new OdiiStorySummary(
                story.storyId(),
                story.title(),
                story.audioTitle(),
                story.category(),
                story.region(),
                story.coordinates(),
                story.durationSeconds(),
                publicImageUrl(story.imageUrl()),
                approvedPlaceLinkQuery.findApprovedPlace(story.spotId(), effectiveMemberId)
                        .map(link -> link.place().placeId())
                        .orElse(null),
                savedStateLookup.savedBy(effectiveMemberId, story.storyId()));
    }

    private String publicImageUrl(String imageUrl) {
        return audioUrlPolicy.allows(imageUrl) ? imageUrl : null;
    }

    private OdiiCoverageStatus detailCoverage(
            OdiiLanguageStatus languageStatus,
            OdiiTranscriptStatus transcriptStatus) {
        if (transcriptStatus == OdiiTranscriptStatus.MISSING) {
            return OdiiCoverageStatus.MISSING;
        }
        if (languageStatus != OdiiLanguageStatus.EXACT
                || transcriptStatus == OdiiTranscriptStatus.ESTIMATED) {
            return OdiiCoverageStatus.PARTIAL;
        }
        return OdiiCoverageStatus.COMPLETE;
    }

    private boolean isAfterCursor(OdiiStoryProjection story, OdiiStoryCursor cursor) {
        int publishedComparison = story.publishedAt().compareTo(cursor.publishedAt());
        if (publishedComparison < 0) {
            return true;
        }
        return publishedComparison == 0 && story.storyId().compareTo(cursor.storyId()) > 0;
    }

    private String encodeCursor(UUID revisionId, OdiiStoryQuery query, OdiiStoryProjection story) {
        return cursorCodec.encode(new OdiiStoryCursor(
                revisionId,
                query.language(),
                query.category(),
                query.regionCode(),
                query.limit(),
                story.publishedAt(),
                story.storyId()));
    }

    private OdiiStoryCursor decodeCursor(
            String cursor,
            UUID activeRevisionId,
            OdiiStoryQuery query) {
        if (cursor == null) {
            return null;
        }
        var decoded = cursorCodec.decode(cursor);
        if (!activeRevisionId.equals(decoded.revisionId())) {
            throw new OdiiCursorExpiredException();
        }
        if (!decoded.matches(query)) {
            throw new OdiiCursorInvalidException();
        }
        return decoded;
    }

    private record LanguageSelection(
            String language,
            OdiiLanguageStatus status,
            List<OdiiStoryProjection> stories) {
    }
}
