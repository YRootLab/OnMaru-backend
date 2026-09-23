package com.yrootlab.onmaru.audio.query;

import com.yrootlab.onmaru.audio.placelink.ApprovedAudioPlaceLinkQuery;
import com.yrootlab.onmaru.audio.sync.AudioStatus;
import com.yrootlab.onmaru.catalog.application.tags.ContentTagExtractor;
import com.yrootlab.onmaru.catalog.application.tags.ContentTagPipeline;
import com.yrootlab.onmaru.catalog.application.tags.ContentTagSource;

import java.time.Instant;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

public final class OdiiStoryQueryService {

    private static final String SCHEMA_VERSION = "1.2";
    private static final String FALLBACK_LANGUAGE = "ko-KR";
    private static final Pattern LANGUAGE_PATTERN = Pattern.compile("^[a-z]{2}(-[A-Z]{2})?$");
    private static final Pattern STORY_ID_PATTERN = Pattern.compile("^odii-story-[a-z0-9-]{3,80}$");
    private static final int MAX_CONTENT_TAGS = 7;
    private static final Comparator<OdiiStoryProjection> ORDER = Comparator
            .comparing(OdiiStoryProjection::publishedAt).reversed()
            .thenComparing(OdiiStoryProjection::storyId);

    private final OdiiStoryQueryStore store;
    private final OdiiSavedStateLookup savedStateLookup;
    private final ApprovedAudioPlaceLinkQuery approvedPlaceLinkQuery;
    private final OdiiPublicAudioUrlPolicy audioUrlPolicy;
    private final OdiiStoryCursorCodec cursorCodec;
    private final ContentTagPipeline contentTagPipeline;
    private final OdiiStoryPopularityPort popularityPort;

    public OdiiStoryQueryService(
            OdiiStoryQueryStore store,
            OdiiSavedStateLookup savedStateLookup,
            ApprovedAudioPlaceLinkQuery approvedPlaceLinkQuery,
            OdiiPublicAudioUrlPolicy audioUrlPolicy,
            OdiiStoryCursorCodec cursorCodec) {
        this(store, savedStateLookup, approvedPlaceLinkQuery, audioUrlPolicy, cursorCodec,
                ContentTagPipeline.defaultPipeline(), noopPopularity());
    }

    public OdiiStoryQueryService(
            OdiiStoryQueryStore store,
            OdiiSavedStateLookup savedStateLookup,
            ApprovedAudioPlaceLinkQuery approvedPlaceLinkQuery,
            OdiiPublicAudioUrlPolicy audioUrlPolicy,
            OdiiStoryCursorCodec cursorCodec,
            ContentTagExtractor contentTagExtractor) {
        this(store, savedStateLookup, approvedPlaceLinkQuery, audioUrlPolicy, cursorCodec,
                ContentTagPipeline.of(contentTagExtractor), noopPopularity());
    }

    public OdiiStoryQueryService(
            OdiiStoryQueryStore store,
            OdiiSavedStateLookup savedStateLookup,
            ApprovedAudioPlaceLinkQuery approvedPlaceLinkQuery,
            OdiiPublicAudioUrlPolicy audioUrlPolicy,
            OdiiStoryCursorCodec cursorCodec,
            ContentTagPipeline contentTagPipeline) {
        this(store, savedStateLookup, approvedPlaceLinkQuery, audioUrlPolicy, cursorCodec,
                contentTagPipeline, noopPopularity());
    }

    public OdiiStoryQueryService(
            OdiiStoryQueryStore store,
            OdiiSavedStateLookup savedStateLookup,
            ApprovedAudioPlaceLinkQuery approvedPlaceLinkQuery,
            OdiiPublicAudioUrlPolicy audioUrlPolicy,
            OdiiStoryCursorCodec cursorCodec,
            ContentTagPipeline contentTagPipeline,
            OdiiStoryPopularityPort popularityPort) {
        this.store = store;
        this.savedStateLookup = savedStateLookup;
        this.approvedPlaceLinkQuery = approvedPlaceLinkQuery;
        this.audioUrlPolicy = audioUrlPolicy;
        this.cursorCodec = cursorCodec;
        this.contentTagPipeline = contentTagPipeline;
        this.popularityPort = popularityPort;
    }

    private static OdiiStoryPopularityPort noopPopularity() {
        return new OdiiStoryPopularityPort() {
            @Override
            public void recordPlay(String storyId, Instant occurredAt) {
            }

            @Override
            public void recordSave(String storyId, Instant occurredAt) {
            }

            @Override
            public Map<String, Long> playCounts(Collection<String> storyIds, Instant sinceInclusive) {
                return Map.of();
            }

            @Override
            public Map<String, Long> saveCounts(Collection<String> storyIds, Instant sinceInclusive) {
                return Map.of();
            }
        };
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

    public OdiiStoryPage search(
            String keyword,
            String language,
            int limit,
            Optional<UUID> memberId) {
        String normalizedKeyword = validateKeyword(keyword);
        validateLanguage(language);
        validateLimit(limit, "limit", 50);

        var snapshot = store.activeSnapshot();
        var selection = selectLanguage(publicStories(snapshot.stories()), language);
        var matches = deduplicate(selection.stories().stream()
                .filter(story -> searchableText(story).contains(normalizedKeyword))
                .sorted(ORDER)
                .limit(limit)
                .toList());
        return page(selection, matches, memberId);
    }

    public OdiiStoryPage nearby(
            double latitude,
            double longitude,
            double radiusMeters,
            String language,
            int limit,
            Optional<UUID> memberId) {
        validateCoordinate(latitude, -90, 90, "lat");
        validateCoordinate(longitude, -180, 180, "lng");
        if (!Double.isFinite(radiusMeters) || radiusMeters <= 0 || radiusMeters > 50_000) {
            throw new OdiiStoryInvalidRequestException("radius");
        }
        validateLanguage(language);
        validateLimit(limit, "limit", 50);

        var snapshot = store.activeSnapshot();
        var selection = selectLanguage(publicStories(snapshot.stories()), language);
        var nearby = selection.stories().stream()
                .map(story -> new Distance(story, distanceMeters(
                        latitude, longitude, story.coordinates().lat(), story.coordinates().lng())))
                .filter(distance -> distance.meters() <= radiusMeters)
                .sorted(Comparator.comparingDouble(Distance::meters)
                        .thenComparing(distance -> distance.story().publishedAt(), Comparator.reverseOrder())
                        .thenComparing(distance -> distance.story().storyId()))
                .map(Distance::story)
                .limit(limit)
                .toList();
        return page(selection, deduplicate(nearby), memberId);
    }

    public OdiiStoryPage recommend(
            String keyword,
            String language,
            int limit,
            Optional<UUID> memberId) {
        String normalizedKeyword = validateKeyword(keyword);
        validateLanguage(language);
        validateLimit(limit, "limit", 50);

        var snapshot = store.activeSnapshot();
        var selection = selectLanguage(publicStories(snapshot.stories()), language);
        var ranked = selection.stories().stream()
                .filter(story -> searchableText(story).contains(normalizedKeyword))
                .sorted(Comparator.comparingInt((OdiiStoryProjection story) -> matchScore(story, normalizedKeyword))
                        .reversed()
                        .thenComparing(OdiiStoryProjection::publishedAt, Comparator.reverseOrder())
                        .thenComparing(OdiiStoryProjection::storyId))
                .limit(limit)
                .toList();
        return page(selection, deduplicate(ranked), memberId);
    }

    private List<OdiiStoryProjection> publicStories(List<OdiiStoryProjection> stories) {
        return stories.stream().filter(this::isPublicAndPlayable).toList();
    }

    private OdiiStoryPage page(
            LanguageSelection selection,
            List<OdiiStoryProjection> stories,
            Optional<UUID> memberId) {
        var summaries = stories.stream().map(story -> summary(story, memberId)).toList();
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
                null,
                false);
    }

    private String validateKeyword(String keyword) {
        if (keyword == null || keyword.isBlank() || keyword.trim().length() > 80) {
            throw new OdiiStoryInvalidRequestException("keyword");
        }
        return normalize(keyword);
    }

    private void validateLimit(int limit, String field, int maximum) {
        if (limit < 1 || limit > maximum) {
            throw new OdiiStoryInvalidRequestException(field);
        }
    }

    private void validateCoordinate(double value, double minimum, double maximum, String field) {
        if (!Double.isFinite(value) || value < minimum || value > maximum) {
            throw new OdiiStoryInvalidRequestException(field);
        }
    }

    private String searchableText(OdiiStoryProjection story) {
        return normalize(String.join(" ",
                nullToEmpty(story.title()),
                nullToEmpty(story.audioTitle()),
                String.join(" ", story.contentTags())));
    }

    private int matchScore(OdiiStoryProjection story, String keyword) {
        int score = 0;
        if (normalize(nullToEmpty(story.title())).equals(keyword)) {
            score += 100;
        } else if (normalize(nullToEmpty(story.title())).contains(keyword)) {
            score += 60;
        }
        if (normalize(nullToEmpty(story.audioTitle())).contains(keyword)) {
            score += 40;
        }
        if (story.contentTags().stream().map(this::normalize).anyMatch(tag -> tag.equals(keyword))) {
            score += 30;
        } else if (story.contentTags().stream().map(this::normalize).anyMatch(tag -> tag.contains(keyword))) {
            score += 15;
        }
        return score;
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private String normalize(String value) {
        return value.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    private double distanceMeters(double lat1, double lon1, double lat2, double lon2) {
        double earthRadiusMeters = 6_371_000;
        double latDelta = Math.toRadians(lat2 - lat1);
        double lonDelta = Math.toRadians(lon2 - lon1);
        double a = Math.sin(latDelta / 2) * Math.sin(latDelta / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(lonDelta / 2) * Math.sin(lonDelta / 2);
        return earthRadiusMeters * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    private record Distance(OdiiStoryProjection story, double meters) {
    }

    /**
     * 광역 지역 그룹별 활성 오디오 스토리 수를 조회한다.
     *
     * <p>요청 언어와 동일한 스토리가 있으면 그 언어만 세고, 없으면 기본 언어(ko-KR)로
     * 폴백해 카운트한다. 그룹은 {@link OdiiRegionGroupCatalog}의 선언 순서를 따르고,
     * 스토리가 없는 그룹은 응답에서 제외한다.</p>
     */
    public OdiiRegionGroupsPage regionGroups(String language) {
        validateLanguage(language);
        var snapshot = store.activeSnapshot();
        var eligible = snapshot.stories().stream()
                .filter(this::isPublicAndPlayable)
                .toList();
        var selection = selectLanguage(eligible, language);
        var deduplicated = deduplicate(selection.stories());

        var counts = new LinkedHashMap<String, Long>();
        var provinceCodes = new LinkedHashMap<String, java.util.LinkedHashSet<String>>();
        for (var story : deduplicated) {
            String label = OdiiRegionGroupCatalog.groupLabel(story.region());
            counts.merge(label, 1L, Long::sum);
            String provinceCode = provinceCodeOf(story.region());
            provinceCodes.computeIfAbsent(label, key -> new java.util.LinkedHashSet<>()).add(provinceCode);
        }

        var groups = OdiiRegionGroupCatalog.groupOrder().stream()
                .filter(counts::containsKey)
                .map(label -> new OdiiRegionGroup(
                        label,
                        List.copyOf(provinceCodes.get(label)),
                        counts.get(label)))
                .toList();
        return new OdiiRegionGroupsPage(
                SCHEMA_VERSION,
                selection.language(),
                selection.status(),
                groups);
    }

    private String provinceCodeOf(OdiiRegionRef region) {
        if (region.parentRegionCode() != null && !region.parentRegionCode().isBlank()) {
            return region.parentRegionCode();
        }
        return region.regionCode();
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

    public OdiiSavedStoryProjection savedStory(String storyId, Optional<UUID> memberId) {
        if (storyId == null || !STORY_ID_PATTERN.matcher(storyId).matches()) {
            throw new OdiiStoryNotFoundException();
        }
        var candidates = store.activeSnapshot().stories().stream()
                .filter(story -> storyId.equals(story.storyId()))
                .filter(this::isPublicAndPlayable)
                .toList();
        var selection = selectLanguage(candidates, FALLBACK_LANGUAGE);
        var story = selection.stories().stream().sorted(ORDER).findFirst()
                .orElseThrow(OdiiStoryNotFoundException::new);
        var effectiveMemberId = memberId == null ? Optional.<UUID>empty() : memberId;
        return new OdiiSavedStoryProjection(
                story.storyId(),
                story.spotId(),
                story.title(),
                approvedPlaceLinkQuery.findApprovedPlace(story.spotId(), effectiveMemberId)
                        .map(link -> link.place().placeId())
                        .orElse(null),
                story.durationSeconds());
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

    /**
     * 인기 점수(2 × 재생 수 + 저장 수) 기준 오디오 스토리 랭킹을 조회한다.
     *
     * <p>{@code sinceInclusive} 이후의 재생·저장 신호로 점수를 매기고, 모든 점수가 0이면
     * 최근 게시 순으로 폴백한다({@code basis: FALLBACK_RECENT}). 커서 없는 TOP N 계약이다.</p>
     */
    public OdiiPopularSoundsPage popular(OdiiPopularSoundsQuery query) {
        validateLanguage(query.language());
        if (query.limit() < 1 || query.limit() > 20) {
            throw new OdiiStoryInvalidRequestException("limit");
        }
        if (query.category() != null && query.category().length() > 80) {
            throw new OdiiStoryInvalidRequestException("category");
        }
        var snapshot = store.activeSnapshot();
        var eligible = snapshot.stories().stream()
                .filter(this::isPublicAndPlayable)
                .filter(story -> query.category() == null || query.category().equals(story.category()))
                .toList();
        var selection = selectLanguage(eligible, query.language());
        var deduplicated = deduplicate(selection.stories());

        var storyIds = deduplicated.stream().map(OdiiStoryProjection::storyId).toList();
        var plays = popularityPort.playCounts(storyIds, query.sinceInclusive());
        var saves = popularityPort.saveCounts(storyIds, query.sinceInclusive());
        boolean hasSignal = deduplicated.stream()
                .anyMatch(story -> scoreOf(story, plays, saves) > 0);

        Comparator<OdiiStoryProjection> ranking = hasSignal
                ? Comparator.<OdiiStoryProjection>comparingLong(
                        story -> -scoreOf(story, plays, saves))
                .thenComparing(OdiiStoryProjection::publishedAt, Comparator.reverseOrder())
                .thenComparing(OdiiStoryProjection::storyId)
                : (left, right) -> {
                    int comparison = right.publishedAt().compareTo(left.publishedAt());
                    return comparison != 0 ? comparison : left.storyId().compareTo(right.storyId());
                };

        var ranked = deduplicated.stream().sorted(ranking)
                .limit(query.limit())
                .toList();
        var items = new java.util.ArrayList<OdiiPopularSoundItem>(ranked.size());
        for (int index = 0; index < ranked.size(); index++) {
            var story = ranked.get(index);
            long score = hasSignal ? scoreOf(story, plays, saves) : 0;
            items.add(new OdiiPopularSoundItem(
                    index + 1,
                    score,
                    plays.getOrDefault(story.storyId(), 0L),
                    saves.getOrDefault(story.storyId(), 0L),
                    summary(story, query.memberId())));
        }
        return new OdiiPopularSoundsPage(
                SCHEMA_VERSION,
                hasSignal ? "POPULARITY" : "FALLBACK_RECENT",
                "week",
                selection.language(),
                selection.status(),
                items);
    }

    private long scoreOf(
            OdiiStoryProjection story,
            Map<String, Long> plays,
            Map<String, Long> saves) {
        return 2 * plays.getOrDefault(story.storyId(), 0L)
                + saves.getOrDefault(story.storyId(), 0L);
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
                contentTags(story),
                savedStateLookup.savedBy(effectiveMemberId, story.storyId()));
    }

    private List<String> contentTags(OdiiStoryProjection story) {
        if (!story.contentTags().isEmpty()) {
            return story.contentTags();
        }
        var transcriptText = story.transcript().stream()
                .map(OdiiTranscriptLine::text)
                .toList();
        return contentTagPipeline.generate(ContentTagSource.of(
                story.title(),
                story.category(),
                story.audioTitle(),
                transcriptText), MAX_CONTENT_TAGS).publicLabels();
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
