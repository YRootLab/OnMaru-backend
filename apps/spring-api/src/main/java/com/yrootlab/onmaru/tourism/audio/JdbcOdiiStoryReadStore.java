package com.yrootlab.onmaru.tourism.audio;

import com.yrootlab.onmaru.audio.query.OdiiCoordinates;
import com.yrootlab.onmaru.audio.query.OdiiLanguageStatus;
import com.yrootlab.onmaru.audio.query.OdiiProjectionMetadataResolver;
import com.yrootlab.onmaru.audio.query.OdiiPopularReadCandidate;
import com.yrootlab.onmaru.audio.query.OdiiPopularReadSelection;
import com.yrootlab.onmaru.audio.query.OdiiStoryProjection;
import com.yrootlab.onmaru.audio.query.OdiiStoryReadPage;
import com.yrootlab.onmaru.audio.query.OdiiStoryReadSelection;
import com.yrootlab.onmaru.audio.query.OdiiStoryRelationalReadPort;
import com.yrootlab.onmaru.audio.query.OdiiStoryTheme;
import com.yrootlab.onmaru.audio.query.OdiiTranscriptLine;
import com.yrootlab.onmaru.audio.query.OdiiTranscriptStatus;
import com.yrootlab.onmaru.audio.sync.AudioStatus;
import com.yrootlab.onmaru.audio.sync.OdiiSpotIdentity;
import com.yrootlab.onmaru.audio.sync.OdiiSpotVersion;
import com.yrootlab.onmaru.audio.sync.TranscriptProvenance;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * 활성 ODII revision을 PostgreSQL에서 직접 필터링하는 공개 조회 adapter.
 * 목록은 script와 subtitle을 읽지 않고, 상세만 요청한 story의 subtitle을 추가 조회한다.
 */
public final class JdbcOdiiStoryReadStore implements OdiiStoryRelationalReadPort {

    private static final String STORY_PREFIX = "odii-story-";
    private static final String SPOT_PREFIX = "odii-spot-";
    private static final String FALLBACK_LANGUAGE = "ko-KR";
    private static final String THEME_FILTER_SQL = """
                  AND (?::text[] IS NULL OR EXISTS (
                      SELECT 1 FROM unnest(?::text[]) AS theme_term(term)
                      WHERE position(theme_term.term IN lower(story_version.title)) > 0
                         OR position(theme_term.term IN lower(spot_version.title)) > 0
                         OR EXISTS (
                             SELECT 1 FROM onmaru.audio_story_content_tag_versions theme_tag
                             WHERE theme_tag.revision_id = story_version.revision_id
                               AND theme_tag.story_id = story_version.story_id
                               AND position(theme_term.term IN lower(theme_tag.label)) > 0
                         )
                  ))
            """;
    private static final String CANDIDATE_THEME_FILTER_SQL = THEME_FILTER_SQL
            .replace("story_version", "candidate")
            .replace("spot_version", "candidate_spot");

    private static final String BASE_COLUMNS = """
            active.revision_id,
            identity.id AS internal_story_id,
            identity.public_id AS public_story_id,
            identity.provider,
            identity.stid,
            identity.stlid,
            identity.lang_code,
            spot_identity.public_id AS public_spot_id,
            spot_identity.provider AS spot_provider,
            spot_identity.tid,
            spot_identity.tlid,
            spot_identity.lang_code AS spot_lang_code,
            spot_version.title AS spot_title,
            ST_X(spot_version.location::geometry) AS longitude,
            ST_Y(spot_version.location::geometry) AS latitude,
            spot_version.source_modified_at AS spot_source_modified_at,
            spot_version.status AS spot_status,
            spot_version.hash AS spot_hash,
            story_version.title AS story_title,
            story_version.audio_url,
            story_version.image_url,
            story_version.duration_seconds,
            story_version.source_modified_at,
            story_version.status AS story_status,
            story_version.transcript_provenance,
            COALESCE(tags.labels, ARRAY[]::varchar[]) AS content_tags
            """;

    private final DataSource dataSource;
    private final String dataset;
    private final String category;
    private final String[] publicAudioHosts;
    private final OdiiProjectionMetadataResolver metadataResolver;

    public JdbcOdiiStoryReadStore(
            DataSource dataSource,
            String dataset,
            String category,
            Set<String> publicAudioHosts,
            OdiiProjectionMetadataResolver metadataResolver
    ) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        if (dataset == null || dataset.isBlank()) {
            throw new IllegalArgumentException("dataset must not be blank");
        }
        if (category == null || category.isBlank()) {
            throw new IllegalArgumentException("category must not be blank");
        }
        this.dataset = dataset;
        this.category = category;
        this.publicAudioHosts = Objects.requireNonNull(publicAudioHosts, "publicAudioHosts").stream()
                .map(String::trim)
                .map(host -> host.toLowerCase(Locale.ROOT))
                .filter(host -> !host.isBlank())
                .toArray(String[]::new);
        if (this.publicAudioHosts.length == 0) {
            throw new IllegalArgumentException("publicAudioHosts must not be empty");
        }
        this.metadataResolver = Objects.requireNonNull(metadataResolver, "metadataResolver");
    }

    @Override
    public OdiiStoryReadPage list(
            String language,
            int limit,
            Instant cursorPublishedAt,
            String cursorStoryId
    ) {
        return listFiltered(language, null, limit, cursorPublishedAt, cursorStoryId);
    }

    @Override
    public OdiiStoryReadPage listTheme(
            String language,
            OdiiStoryTheme theme,
            int limit,
            Instant cursorPublishedAt,
            String cursorStoryId
    ) {
        return listFiltered(language, Objects.requireNonNull(theme), limit, cursorPublishedAt, cursorStoryId);
    }

    private OdiiStoryReadPage listFiltered(
            String language,
            OdiiStoryTheme theme,
            int limit,
            Instant cursorPublishedAt,
            String cursorStoryId
    ) {
        String requestedProviderLanguage = providerLanguage(language);
        UUID cursorPublicId = cursorStoryId == null ? null : parsePublicStoryId(cursorStoryId);
        String sql = """
                WITH active AS (
                    SELECT revision_id
                    FROM onmaru.catalog_active_datasets
                    WHERE dataset = ?
                ), effective_language AS (
                    SELECT CASE WHEN EXISTS (
                        SELECT 1
                        FROM active
                        JOIN onmaru.audio_story_versions candidate
                          ON candidate.revision_id = active.revision_id
                        JOIN onmaru.audio_odii_stories candidate_identity
                          ON candidate_identity.id = candidate.story_id
                        JOIN onmaru.audio_spot_versions candidate_spot
                          ON candidate_spot.revision_id = candidate.revision_id
                         AND candidate_spot.spot_id = candidate.spot_id
                        WHERE candidate_identity.lang_code = ?
                          AND candidate.status = 'ACTIVE'
                          AND candidate_spot.status = 'ACTIVE'
                          AND candidate_spot.location IS NOT NULL
                          AND candidate.source_modified_at IS NOT NULL
                          AND candidate.audio_url IS NOT NULL
                          AND candidate.audio_url ~* '^https://'
                          AND candidate.audio_url !~ '[[:space:][:cntrl:]]'
                          AND candidate.audio_url !~ '%%($|[^0-9A-Fa-f]|[0-9A-Fa-f]($|[^0-9A-Fa-f]))'
                          AND position('?' in candidate.audio_url) = 0
                          AND position('#' in candidate.audio_url) = 0
                          AND lower(regexp_replace(
                              split_part(split_part(candidate.audio_url, '://', 2), '/', 1),
                              ':[0-9]+$', ''))
                              = ANY (?::text[])
                          %s
                    ) THEN ? ELSE 'ko' END AS lang_code
                )
                SELECT %s
                FROM active
                CROSS JOIN effective_language
                JOIN onmaru.audio_story_versions story_version
                  ON story_version.revision_id = active.revision_id
                JOIN onmaru.audio_odii_stories identity
                  ON identity.id = story_version.story_id
                 AND identity.lang_code = effective_language.lang_code
                JOIN onmaru.audio_spot_versions spot_version
                  ON spot_version.revision_id = story_version.revision_id
                 AND spot_version.spot_id = story_version.spot_id
                JOIN onmaru.audio_odii_spots spot_identity
                  ON spot_identity.id = spot_version.spot_id
                LEFT JOIN LATERAL (
                    SELECT array_agg(tag.label ORDER BY tag.position) AS labels
                    FROM onmaru.audio_story_content_tag_versions tag
                    WHERE tag.revision_id = story_version.revision_id
                      AND tag.story_id = story_version.story_id
                ) tags ON true
                WHERE story_version.status = 'ACTIVE'
                  AND spot_version.status = 'ACTIVE'
                  AND spot_version.location IS NOT NULL
                  AND story_version.source_modified_at IS NOT NULL
                  AND story_version.audio_url IS NOT NULL
                  AND story_version.audio_url ~* '^https://'
                  AND story_version.audio_url !~ '[[:space:][:cntrl:]]'
                  AND story_version.audio_url !~ '%%($|[^0-9A-Fa-f]|[0-9A-Fa-f]($|[^0-9A-Fa-f]))'
                  AND position('?' in story_version.audio_url) = 0
                  AND position('#' in story_version.audio_url) = 0
                  AND lower(regexp_replace(
                      split_part(split_part(story_version.audio_url, '://', 2), '/', 1),
                      ':[0-9]+$', ''))
                      = ANY (?::text[])
                  AND (
                      ?::timestamptz IS NULL
                      OR story_version.source_modified_at < ?::timestamptz
                      OR (
                          story_version.source_modified_at = ?::timestamptz
                          AND identity.public_id > ?::uuid
                      )
                  )
                %s
                ORDER BY story_version.source_modified_at DESC, identity.public_id
                LIMIT ?
                """.formatted(CANDIDATE_THEME_FILTER_SQL, BASE_COLUMNS, THEME_FILTER_SQL);
        try (Connection connection = dataSource.getConnection()) {
            configureReadTransaction(connection);
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                int parameter = 1;
                statement.setString(parameter++, dataset);
                statement.setString(parameter++, requestedProviderLanguage);
                statement.setArray(parameter++, connection.createArrayOf("text", publicAudioHosts));
                parameter = bindTheme(statement, connection, parameter, theme);
                statement.setString(parameter++, requestedProviderLanguage);
                statement.setArray(parameter++, connection.createArrayOf("text", publicAudioHosts));
                setInstant(statement, parameter++, cursorPublishedAt);
                setInstant(statement, parameter++, cursorPublishedAt);
                setInstant(statement, parameter++, cursorPublishedAt);
                if (cursorPublicId == null) {
                    statement.setNull(parameter++, Types.OTHER);
                } else {
                    statement.setObject(parameter++, cursorPublicId);
                }
                parameter = bindTheme(statement, connection, parameter, theme);
                statement.setInt(parameter, limit + 1);
                try (ResultSet resultSet = statement.executeQuery()) {
                    var rows = readRows(resultSet, false, connection);
                    boolean hasMore = rows.size() > limit;
                    var pageRows = hasMore ? rows.subList(0, limit) : rows;
                    String resolvedLanguage = pageRows.isEmpty()
                            ? resolveActiveLanguage(connection, language)
                            : pageRows.getFirst().projection().language();
                    String effectiveLanguage = resolvedLanguage == null ? language : resolvedLanguage;
                    long totalCount = countActiveStories(connection, effectiveLanguage, theme);
                    var page = new OdiiStoryReadPage(
                            activeRevision(connection),
                            effectiveLanguage,
                            languageStatus(language, effectiveLanguage, resolvedLanguage == null),
                            pageRows.stream().map(StoryRow::projection).toList(),
                            totalCount,
                            hasMore);
                    connection.commit();
                    return page;
                }
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed to query the active ODII story page", exception);
        }
    }

    @Override
    public OdiiStoryReadSelection detail(String storyId, String language) {
        UUID publicStoryId = parsePublicStoryId(storyId);
        String requestedProviderLanguage = providerLanguage(language);
        String sql = """
                WITH active AS (
                    SELECT revision_id
                    FROM onmaru.catalog_active_datasets
                    WHERE dataset = ?
                ), effective_language AS (
                    SELECT CASE WHEN EXISTS (
                        SELECT 1 FROM active
                        JOIN onmaru.audio_odii_stories candidate
                          ON candidate.public_id = ?
                         AND candidate.lang_code = ?
                        JOIN onmaru.audio_story_versions candidate_version
                          ON candidate_version.revision_id = active.revision_id
                         AND candidate_version.story_id = candidate.id
                        JOIN onmaru.audio_spot_versions candidate_spot
                          ON candidate_spot.revision_id = candidate_version.revision_id
                         AND candidate_spot.spot_id = candidate_version.spot_id
                        WHERE candidate_version.status = 'ACTIVE'
                          AND candidate_spot.status = 'ACTIVE'
                          AND candidate_spot.location IS NOT NULL
                          AND candidate_version.source_modified_at IS NOT NULL
                          AND candidate_version.audio_url IS NOT NULL
                          AND candidate_version.audio_url ~* '^https://'
                          AND candidate_version.audio_url !~ '[[:space:][:cntrl:]]'
                          AND candidate_version.audio_url !~ '%%($|[^0-9A-Fa-f]|[0-9A-Fa-f]($|[^0-9A-Fa-f]))'
                          AND position('?' in candidate_version.audio_url) = 0
                          AND position('#' in candidate_version.audio_url) = 0
                          AND lower(regexp_replace(
                              split_part(split_part(candidate_version.audio_url, '://', 2), '/', 1),
                              ':[0-9]+$', ''))
                              = ANY (?::text[])
                    ) THEN ? ELSE 'ko' END AS lang_code
                )
                SELECT %s
                FROM active
                CROSS JOIN effective_language
                JOIN onmaru.audio_odii_stories identity
                  ON identity.public_id = ?
                 AND identity.lang_code = effective_language.lang_code
                JOIN onmaru.audio_story_versions story_version
                  ON story_version.revision_id = active.revision_id
                 AND story_version.story_id = identity.id
                JOIN onmaru.audio_spot_versions spot_version
                  ON spot_version.revision_id = story_version.revision_id
                 AND spot_version.spot_id = story_version.spot_id
                JOIN onmaru.audio_odii_spots spot_identity
                  ON spot_identity.id = spot_version.spot_id
                LEFT JOIN LATERAL (
                    SELECT array_agg(tag.label ORDER BY tag.position) AS labels
                    FROM onmaru.audio_story_content_tag_versions tag
                    WHERE tag.revision_id = story_version.revision_id
                      AND tag.story_id = story_version.story_id
                ) tags ON true
                WHERE story_version.status = 'ACTIVE'
                  AND spot_version.status = 'ACTIVE'
                  AND spot_version.location IS NOT NULL
                  AND story_version.source_modified_at IS NOT NULL
                  AND story_version.audio_url IS NOT NULL
                  AND story_version.audio_url ~* '^https://'
                  AND story_version.audio_url !~ '[[:space:][:cntrl:]]'
                  AND story_version.audio_url !~ '%%($|[^0-9A-Fa-f]|[0-9A-Fa-f]($|[^0-9A-Fa-f]))'
                  AND position('?' in story_version.audio_url) = 0
                  AND position('#' in story_version.audio_url) = 0
                  AND lower(regexp_replace(
                      split_part(split_part(story_version.audio_url, '://', 2), '/', 1),
                      ':[0-9]+$', ''))
                      = ANY (?::text[])
                ORDER BY story_version.source_modified_at DESC
                """.formatted(BASE_COLUMNS);
        try (Connection connection = dataSource.getConnection()) {
            configureReadTransaction(connection);
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, dataset);
                statement.setObject(2, publicStoryId);
                statement.setString(3, requestedProviderLanguage);
                statement.setArray(4, connection.createArrayOf("text", publicAudioHosts));
                statement.setString(5, requestedProviderLanguage);
                statement.setObject(6, publicStoryId);
                statement.setArray(7, connection.createArrayOf("text", publicAudioHosts));
                try (ResultSet resultSet = statement.executeQuery()) {
                    var rows = readRows(resultSet, true, connection);
                    String effectiveLanguage = rows.isEmpty() ? language : rows.getFirst().projection().language();
                    UUID revisionId = rows.isEmpty() ? activeRevision(connection) : rows.getFirst().revisionId();
                    var selection = new OdiiStoryReadSelection(
                            revisionId,
                            effectiveLanguage,
                            languageStatus(language, effectiveLanguage, rows.isEmpty()),
                            rows.stream().map(StoryRow::projection).toList(),
                            rows.size());
                    connection.commit();
                    return selection;
                }
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed to query ODII story detail: " + storyId, exception);
        }
    }

    @Override
    public OdiiStoryReadSelection search(
            String keyword,
            String language,
            int limit,
            boolean ranked
    ) {
        String ranking = ranked
                ? """
                ORDER BY (
                    CASE
                        WHEN lower(spot_version.title) = search_term.needle THEN 100
                        WHEN lower(spot_version.title) LIKE '%' || search_term.needle || '%' THEN 60
                        ELSE 0
                    END
                    + CASE WHEN lower(story_version.title) LIKE '%' || search_term.needle || '%' THEN 40 ELSE 0 END
                    + CASE
                        WHEN EXISTS (
                            SELECT 1 FROM onmaru.audio_story_content_tag_versions exact_tag
                            WHERE exact_tag.revision_id = story_version.revision_id
                              AND exact_tag.story_id = story_version.story_id
                              AND lower(exact_tag.label) = search_term.needle
                        ) THEN 30
                        WHEN EXISTS (
                            SELECT 1 FROM onmaru.audio_story_content_tag_versions partial_tag
                            WHERE partial_tag.revision_id = story_version.revision_id
                              AND partial_tag.story_id = story_version.story_id
                              AND lower(partial_tag.label) LIKE '%' || search_term.needle || '%'
                        ) THEN 15
                        ELSE 0
                    END
                ) DESC, story_version.source_modified_at DESC, identity.public_id
                """
                : "ORDER BY story_version.source_modified_at DESC, identity.public_id\n";
        String sql = commonSelectionSql(
                ", search_term AS (SELECT ?::text AS needle)",
                "CROSS JOIN search_term",
                """
                AND (
                    lower(spot_version.title) LIKE '%' || search_term.needle || '%'
                    OR lower(story_version.title) LIKE '%' || search_term.needle || '%'
                    OR EXISTS (
                        SELECT 1 FROM onmaru.audio_story_content_tag_versions matching_tag
                        WHERE matching_tag.revision_id = story_version.revision_id
                          AND matching_tag.story_id = story_version.story_id
                          AND lower(matching_tag.label) LIKE '%' || search_term.needle || '%'
                    )
                )
                """,
                ranking + "LIMIT ?");
        return querySelection(sql, language, statement -> {
            statement.setString(4, keyword);
            statement.setInt(5, limit);
        });
    }

    @Override
    public OdiiStoryReadSelection nearby(
            double latitude,
            double longitude,
            double radiusMeters,
            String language,
            int limit
    ) {
        String sql = commonSelectionSql(
                ", origin AS (SELECT ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography AS point)",
                "CROSS JOIN origin",
                "AND ST_DWithin(spot_version.location, origin.point, ?)",
                """
                ORDER BY ST_Distance(spot_version.location, origin.point),
                         story_version.source_modified_at DESC,
                         identity.public_id
                LIMIT ?
                """);
        return querySelection(sql, language, statement -> {
            statement.setDouble(4, longitude);
            statement.setDouble(5, latitude);
            statement.setDouble(6, radiusMeters);
            statement.setInt(7, limit);
        });
    }

    @Override
    public OdiiPopularReadSelection popular(
            String language,
            String requestedCategory,
            Instant sinceInclusive,
            int limit
    ) {
        if (requestedCategory != null && !category.equals(requestedCategory)) {
            return new OdiiPopularReadSelection(
                    language, OdiiLanguageStatus.MISSING, List.of(), false);
        }
        String sql = commonSelectionSql(
                """
                , plays AS (
                    SELECT story_id, count(*) AS play_count
                    FROM onmaru.audio_story_play_events
                    WHERE occurred_at >= ?
                    GROUP BY story_id
                ), saves AS (
                    SELECT story_id, count(*) AS save_count
                    FROM onmaru.journey_saved_odii_stories
                    WHERE saved_at >= ?
                    GROUP BY story_id
                )
                """,
                """
                LEFT JOIN plays
                  ON plays.story_id = 'odii-story-' || identity.public_id::text
                LEFT JOIN saves
                  ON saves.story_id = 'odii-story-' || identity.public_id::text
                """,
                "",
                """
                ORDER BY (2 * COALESCE(plays.play_count, 0) + COALESCE(saves.save_count, 0)) DESC,
                         story_version.source_modified_at DESC,
                         identity.public_id
                LIMIT ?
                """,
                ", COALESCE(plays.play_count, 0) AS play_count, COALESCE(saves.save_count, 0) AS save_count");
        String providerLanguage = providerLanguage(language);
        try (Connection connection = dataSource.getConnection()) {
            configureReadTransaction(connection);
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, dataset);
                statement.setString(2, providerLanguage);
                statement.setArray(3, connection.createArrayOf("text", publicAudioHosts));
                statement.setTimestamp(4, Timestamp.from(sinceInclusive));
                statement.setTimestamp(5, Timestamp.from(sinceInclusive));
                statement.setInt(6, limit);
                try (ResultSet resultSet = statement.executeQuery()) {
                    var rows = readRows(resultSet, false, connection, true);
                    var candidates = new ArrayList<OdiiPopularReadCandidate>(rows.size());
                    for (var row : rows) {
                        candidates.add(new OdiiPopularReadCandidate(
                                row.projection(),
                                row.playCount(),
                                row.saveCount()));
                    }
                    String resolvedLanguage = rows.isEmpty()
                            ? resolveActiveLanguage(connection, language)
                            : rows.getFirst().projection().language();
                    String effectiveLanguage = resolvedLanguage == null ? language : resolvedLanguage;
                    boolean hasSignal = candidates.stream().anyMatch(candidate -> candidate.score() > 0);
                    connection.commit();
                    return new OdiiPopularReadSelection(
                            effectiveLanguage,
                            languageStatus(language, effectiveLanguage, resolvedLanguage == null),
                            candidates,
                            hasSignal);
                }
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed to query popular ODII stories", exception);
        }
    }

    private String commonSelectionSql(
            String additionalCte,
            String additionalJoin,
            String additionalPredicate,
            String orderAndLimit
    ) {
        return commonSelectionSql(
                additionalCte, additionalJoin, additionalPredicate, orderAndLimit, "");
    }

    private String commonSelectionSql(
            String additionalCte,
            String additionalJoin,
            String additionalPredicate,
            String orderAndLimit,
            String additionalColumns
    ) {
        return """
                WITH active AS (
                    SELECT revision_id FROM onmaru.catalog_active_datasets WHERE dataset = ?
                ), request AS (
                    SELECT ?::text AS lang_code, ?::text[] AS public_hosts
                ), effective_language AS (
                    SELECT CASE WHEN EXISTS (
                        SELECT 1
                        FROM active
                        CROSS JOIN request
                        JOIN onmaru.audio_story_versions candidate
                          ON candidate.revision_id = active.revision_id
                        JOIN onmaru.audio_odii_stories candidate_identity
                          ON candidate_identity.id = candidate.story_id
                        JOIN onmaru.audio_spot_versions candidate_spot
                          ON candidate_spot.revision_id = candidate.revision_id
                         AND candidate_spot.spot_id = candidate.spot_id
                        WHERE candidate_identity.lang_code = request.lang_code
                          AND candidate.status = 'ACTIVE'
                          AND candidate_spot.status = 'ACTIVE'
                          AND candidate_spot.location IS NOT NULL
                          AND candidate.source_modified_at IS NOT NULL
                          AND candidate.audio_url ~* '^https://'
                          AND candidate.audio_url !~ '[[:space:][:cntrl:]]'
                          AND candidate.audio_url !~ '%%($|[^0-9A-Fa-f]|[0-9A-Fa-f]($|[^0-9A-Fa-f]))'
                          AND position('?' in candidate.audio_url) = 0
                          AND position('#' in candidate.audio_url) = 0
                          AND lower(regexp_replace(
                              split_part(split_part(candidate.audio_url, '://', 2), '/', 1),
                              ':[0-9]+$', ''))
                              = ANY (request.public_hosts)
                    ) THEN request.lang_code ELSE 'ko' END AS lang_code
                    FROM request
                )%s
                SELECT %s%s, COUNT(*) OVER() AS total_count
                FROM active
                CROSS JOIN effective_language
                CROSS JOIN request
                JOIN onmaru.audio_story_versions story_version
                  ON story_version.revision_id = active.revision_id
                JOIN onmaru.audio_odii_stories identity
                  ON identity.id = story_version.story_id
                 AND identity.lang_code = effective_language.lang_code
                JOIN onmaru.audio_spot_versions spot_version
                  ON spot_version.revision_id = story_version.revision_id
                 AND spot_version.spot_id = story_version.spot_id
                JOIN onmaru.audio_odii_spots spot_identity
                  ON spot_identity.id = spot_version.spot_id
                %s
                LEFT JOIN LATERAL (
                    SELECT array_agg(tag.label ORDER BY tag.position) AS labels
                    FROM onmaru.audio_story_content_tag_versions tag
                    WHERE tag.revision_id = story_version.revision_id
                      AND tag.story_id = story_version.story_id
                ) tags ON true
                WHERE story_version.status = 'ACTIVE'
                  AND spot_version.status = 'ACTIVE'
                  AND spot_version.location IS NOT NULL
                  AND story_version.source_modified_at IS NOT NULL
                  AND story_version.audio_url ~* '^https://'
                  AND story_version.audio_url !~ '[[:space:][:cntrl:]]'
                  AND story_version.audio_url !~ '%%($|[^0-9A-Fa-f]|[0-9A-Fa-f]($|[^0-9A-Fa-f]))'
                  AND position('?' in story_version.audio_url) = 0
                  AND position('#' in story_version.audio_url) = 0
                  AND lower(regexp_replace(
                      split_part(split_part(story_version.audio_url, '://', 2), '/', 1),
                      ':[0-9]+$', ''))
                      = ANY (request.public_hosts)
                  %s
                %s
                """.formatted(
                additionalCte,
                BASE_COLUMNS,
                additionalColumns,
                additionalJoin,
                additionalPredicate,
                orderAndLimit);
    }

    private OdiiStoryReadSelection querySelection(
            String sql,
            String language,
            StatementBinder binder
    ) {
        String providerLanguage = providerLanguage(language);
        try (Connection connection = dataSource.getConnection()) {
            configureReadTransaction(connection);
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, dataset);
                statement.setString(2, providerLanguage);
                statement.setArray(3, connection.createArrayOf("text", publicAudioHosts));
                binder.bind(statement);
                try (ResultSet resultSet = statement.executeQuery()) {
                    var rows = readRows(resultSet, false, connection, false, true);
                    String resolvedLanguage = rows.isEmpty()
                            ? resolveActiveLanguage(connection, language)
                            : rows.getFirst().projection().language();
                    String effectiveLanguage = resolvedLanguage == null ? language : resolvedLanguage;
                    long totalCount = rows.isEmpty() ? 0 : rows.getFirst().totalCount();
                    var selection = new OdiiStoryReadSelection(
                            rows.isEmpty() ? activeRevision(connection) : rows.getFirst().revisionId(),
                            effectiveLanguage,
                            languageStatus(language, effectiveLanguage, resolvedLanguage == null),
                            rows.stream().map(StoryRow::projection).toList(),
                            totalCount);
                    connection.commit();
                    return selection;
                }
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed to query the active ODII story selection", exception);
        }
    }

    private List<StoryRow> readRows(
            ResultSet resultSet,
            boolean includeTranscript,
            Connection connection
    ) throws SQLException {
        return readRows(resultSet, includeTranscript, connection, false);
    }

    private List<StoryRow> readRows(
            ResultSet resultSet,
            boolean includeTranscript,
            Connection connection,
            boolean includePopularity
    ) throws SQLException {
        return readRows(resultSet, includeTranscript, connection, includePopularity, false);
    }

    private List<StoryRow> readRows(
            ResultSet resultSet,
            boolean includeTranscript,
            Connection connection,
            boolean includePopularity,
            boolean includeTotalCount
    ) throws SQLException {
        var rows = new ArrayList<StoryRow>();
        while (resultSet.next()) {
            UUID revisionId = resultSet.getObject("revision_id", UUID.class);
            UUID internalStoryId = resultSet.getObject("internal_story_id", UUID.class);
            var spotIdentity = new OdiiSpotIdentity(
                    resultSet.getString("spot_provider"),
                    resultSet.getString("tid"),
                    resultSet.getString("tlid"),
                    resultSet.getString("spot_lang_code"));
            var spot = new OdiiSpotVersion(
                    spotIdentity,
                    resultSet.getString("spot_title"),
                    resultSet.getBigDecimal("longitude"),
                    resultSet.getBigDecimal("latitude"),
                    instant(resultSet, "spot_source_modified_at"),
                    AudioStatus.valueOf(resultSet.getString("spot_status")),
                    resultSet.getString("spot_hash"));
            var metadata = metadataResolver.resolve(spot);
            TranscriptProvenance provenance = TranscriptProvenance.valueOf(
                    resultSet.getString("transcript_provenance"));
            OdiiTranscriptStatus transcriptStatus = provenance == TranscriptProvenance.OFFICIAL
                    ? OdiiTranscriptStatus.OFFICIAL
                    : OdiiTranscriptStatus.MISSING;
            List<OdiiTranscriptLine> transcript = includeTranscript && transcriptStatus != OdiiTranscriptStatus.MISSING
                    ? transcript(connection, revisionId, internalStoryId)
                    : List.of();
            var tags = contentTags(resultSet);
            var projection = new OdiiStoryProjection(
                    STORY_PREFIX + resultSet.getObject("public_story_id", UUID.class),
                    SPOT_PREFIX + resultSet.getObject("public_spot_id", UUID.class),
                    publicLanguage(resultSet.getString("lang_code")),
                    resultSet.getString("spot_title"),
                    resultSet.getString("story_title"),
                    OdiiStoryTheme.primaryCategory(
                            spot.title(), resultSet.getString("story_title"), tags,
                            metadata.category() == null ? category : metadata.category()),
                    metadata.region(),
                    new OdiiCoordinates(
                            resultSet.getDouble("latitude"),
                            resultSet.getDouble("longitude")),
                    (Integer) resultSet.getObject("duration_seconds"),
                    resultSet.getString("image_url"),
                    resultSet.getString("audio_url"),
                    transcriptStatus,
                    transcript,
                    tags,
                    instant(resultSet, "source_modified_at"),
                    AudioStatus.valueOf(resultSet.getString("story_status")),
                    AudioStatus.valueOf(resultSet.getString("spot_status")));
            rows.add(new StoryRow(
                    revisionId,
                    projection,
                    includePopularity ? resultSet.getLong("play_count") : 0,
                    includePopularity ? resultSet.getLong("save_count") : 0,
                    includeTotalCount ? resultSet.getLong("total_count") : 0));
        }
        return rows;
    }

    private List<OdiiTranscriptLine> transcript(
            Connection connection,
            UUID revisionId,
            UUID storyId
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT position, text, start_seconds, timing_mode
                FROM onmaru.audio_subtitle_lines
                WHERE revision_id = ? AND story_id = ?
                ORDER BY position
                """)) {
            statement.setObject(1, revisionId);
            statement.setObject(2, storyId);
            try (ResultSet resultSet = statement.executeQuery()) {
                var lines = new ArrayList<OdiiTranscriptLine>();
                while (resultSet.next()) {
                    double startSeconds = resultSet.getBigDecimal("start_seconds") == null
                            ? 0
                            : resultSet.getBigDecimal("start_seconds").doubleValue();
                    lines.add(new OdiiTranscriptLine(
                            resultSet.getInt("position"),
                            startSeconds,
                            resultSet.getString("text")));
                }
                return List.copyOf(lines);
            }
        }
    }

    private List<String> contentTags(ResultSet resultSet) throws SQLException {
        var sqlArray = resultSet.getArray("content_tags");
        if (sqlArray == null) {
            return List.of();
        }
        try {
            return Arrays.stream((Object[]) sqlArray.getArray())
                    .map(String::valueOf)
                    .toList();
        } finally {
            sqlArray.free();
        }
    }

    private UUID activeRevision(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT revision_id
                FROM onmaru.catalog_active_datasets
                WHERE dataset = ?
                """)) {
            statement.setString(1, dataset);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    throw new IllegalStateException("active audio revision is unavailable");
                }
                return resultSet.getObject(1, UUID.class);
            }
        }
    }

    private String resolveActiveLanguage(Connection connection, String requestedLanguage) throws SQLException {
        String requestedProviderLanguage = providerLanguage(requestedLanguage);
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT identity.lang_code
                FROM onmaru.catalog_active_datasets active
                JOIN onmaru.audio_story_versions story_version
                  ON story_version.revision_id = active.revision_id
                JOIN onmaru.audio_odii_stories identity
                  ON identity.id = story_version.story_id
                 AND identity.lang_code IN (?, 'ko')
                JOIN onmaru.audio_spot_versions spot_version
                  ON spot_version.revision_id = story_version.revision_id
                 AND spot_version.spot_id = story_version.spot_id
                WHERE active.dataset = ?
                  AND story_version.status = 'ACTIVE'
                  AND spot_version.status = 'ACTIVE'
                  AND spot_version.location IS NOT NULL
                  AND story_version.source_modified_at IS NOT NULL
                  AND story_version.audio_url ~* '^https://'
                  AND story_version.audio_url !~ '[[:space:][:cntrl:]]'
                  AND story_version.audio_url !~ '%($|[^0-9A-Fa-f]|[0-9A-Fa-f]($|[^0-9A-Fa-f]))'
                  AND position('?' in story_version.audio_url) = 0
                  AND position('#' in story_version.audio_url) = 0
                  AND lower(regexp_replace(
                      split_part(split_part(story_version.audio_url, '://', 2), '/', 1),
                      ':[0-9]+$', ''))
                      = ANY (?::text[])
                GROUP BY identity.lang_code
                ORDER BY CASE WHEN identity.lang_code = ? THEN 0 ELSE 1 END
                LIMIT 1
                """)) {
            statement.setString(1, requestedProviderLanguage);
            statement.setString(2, dataset);
            statement.setArray(3, connection.createArrayOf("text", publicAudioHosts));
            statement.setString(4, requestedProviderLanguage);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return null;
                }
                return publicLanguage(resultSet.getString(1));
            }
        }
    }

    private long countActiveStories(Connection connection, String language, OdiiStoryTheme theme) throws SQLException {
        String sql = """
                SELECT COUNT(*)
                FROM onmaru.catalog_active_datasets active
                JOIN onmaru.audio_story_versions story_version
                  ON story_version.revision_id = active.revision_id
                JOIN onmaru.audio_odii_stories identity
                  ON identity.id = story_version.story_id
                 AND identity.lang_code = ?
                JOIN onmaru.audio_spot_versions spot_version
                  ON spot_version.revision_id = story_version.revision_id
                 AND spot_version.spot_id = story_version.spot_id
                WHERE active.dataset = ?
                  AND story_version.status = 'ACTIVE'
                  AND spot_version.status = 'ACTIVE'
                  AND spot_version.location IS NOT NULL
                  AND story_version.source_modified_at IS NOT NULL
                  AND story_version.audio_url IS NOT NULL
                  AND story_version.audio_url ~* '^https://'
                  AND story_version.audio_url !~ '[[:space:][:cntrl:]]'
                  AND story_version.audio_url !~ '%($|[^0-9A-Fa-f]|[0-9A-Fa-f]($|[^0-9A-Fa-f]))'
                  AND position('?' in story_version.audio_url) = 0
                  AND position('#' in story_version.audio_url) = 0
                  AND lower(regexp_replace(
                      split_part(split_part(story_version.audio_url, '://', 2), '/', 1),
                      ':[0-9]+$', ''))
                      = ANY (?::text[])
                """ + THEME_FILTER_SQL;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, providerLanguage(language));
            statement.setString(2, dataset);
            statement.setArray(3, connection.createArrayOf("text", publicAudioHosts));
            bindTheme(statement, connection, 4, theme);
            try (ResultSet resultSet = statement.executeQuery()) {
                resultSet.next();
                return resultSet.getLong(1);
            }
        }
    }

    private int bindTheme(
            PreparedStatement statement, Connection connection, int parameter, OdiiStoryTheme theme)
            throws SQLException {
        if (theme == null) {
            statement.setNull(parameter++, Types.ARRAY);
            statement.setNull(parameter++, Types.ARRAY);
        } else {
            var terms = connection.createArrayOf("text", theme.terms().toArray(String[]::new));
            statement.setArray(parameter++, terms);
            statement.setArray(parameter++, terms);
        }
        return parameter;
    }

    private OdiiLanguageStatus languageStatus(
            String requested,
            String effective,
            boolean empty
    ) {
        if (empty) {
            return OdiiLanguageStatus.MISSING;
        }
        return requested.equals(effective) ? OdiiLanguageStatus.EXACT : OdiiLanguageStatus.FALLBACK;
    }

    private UUID parsePublicStoryId(String storyId) {
        if (storyId == null || !storyId.startsWith(STORY_PREFIX)) {
            throw new IllegalArgumentException("invalid public ODII story ID");
        }
        try {
            return UUID.fromString(storyId.substring(STORY_PREFIX.length()));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("invalid public ODII story ID", exception);
        }
    }

    private String providerLanguage(String publicLanguage) {
        return switch (publicLanguage) {
            case "ko-KR" -> "ko";
            case "en-US" -> "en";
            case "ja-JP" -> "ja";
            default -> publicLanguage;
        };
    }

    private String publicLanguage(String providerLanguage) {
        return switch (providerLanguage) {
            case "ko" -> "ko-KR";
            case "en" -> "en-US";
            case "ja" -> "ja-JP";
            default -> providerLanguage;
        };
    }

    private Instant instant(ResultSet resultSet, String column) throws SQLException {
        Timestamp timestamp = resultSet.getTimestamp(column);
        return timestamp == null ? null : timestamp.toInstant();
    }

    private void setInstant(PreparedStatement statement, int index, Instant value) throws SQLException {
        if (value == null) {
            statement.setNull(index, Types.TIMESTAMP_WITH_TIMEZONE);
        } else {
            statement.setTimestamp(index, Timestamp.from(value));
        }
    }

    private void configureReadTransaction(Connection connection) throws SQLException {
        connection.setReadOnly(true);
        connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
        connection.setAutoCommit(false);
    }

    private record StoryRow(
            UUID revisionId,
            OdiiStoryProjection projection,
            long playCount,
            long saveCount,
            long totalCount) {
    }

    @FunctionalInterface
    private interface StatementBinder {
        void bind(PreparedStatement statement) throws SQLException;
    }
}
