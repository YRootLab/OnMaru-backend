package com.yrootlab.onmaru.persistence.catalog;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yrootlab.onmaru.catalog.application.qualification.CanonicalCandidate;
import com.yrootlab.onmaru.catalog.application.qualification.QuarantineRecord;
import com.yrootlab.onmaru.catalog.application.qualification.QualificationStatus;
import com.yrootlab.onmaru.catalog.application.qualification.SourceQualificationPolicy;
import com.yrootlab.onmaru.catalog.application.qualification.SourceRecord;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;

public final class JdbcTourApiCatalogPublisher {

    private static final String DATASET = "kto-korean-tour";
    private static final String PROVIDER = "kto-tourapi-korean";
    private static final String LANGUAGE = "ko-KR";
    private static final String BULK_STAGE_SQL = """
            WITH params AS (
                SELECT ?::uuid AS revision_id, ?::timestamptz AS fetched_at
            ), input AS (
                SELECT * FROM jsonb_to_recordset(?::jsonb) AS row(
                    external_id text, raw_hash text, proposed_place_id uuid, proposed_source_id uuid,
                    raw jsonb, publishable boolean, public_id text, name text, category text,
                    longitude double precision, latitude double precision, normalized_hash text,
                    allowlist_version text, tag_label text, qualification_status text
                )
            ), inserted_identities AS (
                INSERT INTO onmaru.catalog_place_identity (id, created_at)
                SELECT DISTINCT i.proposed_place_id, p.fetched_at
                FROM input i CROSS JOIN params p
                WHERE NOT EXISTS (
                    SELECT 1 FROM onmaru.catalog_place_sources source
                    WHERE source.provider = 'kto-tourapi-korean'
                      AND source.dataset = 'kto-korean-tour'
                      AND source.external_id = i.external_id
                      AND source.language = 'ko-KR'
                )
                ON CONFLICT (id) DO NOTHING
                RETURNING id
            ), identity_barrier AS (
                SELECT count(*) AS inserted_count FROM inserted_identities
            ), upserted_sources AS (
                INSERT INTO onmaru.catalog_place_sources
                    (id, place_id, provider, dataset, external_id, language, fetched_at, payload_hash)
                SELECT i.proposed_source_id, i.proposed_place_id, 'kto-tourapi-korean',
                       'kto-korean-tour', i.external_id, 'ko-KR', p.fetched_at, i.raw_hash
                FROM input i CROSS JOIN params p CROSS JOIN identity_barrier
                ON CONFLICT ON CONSTRAINT catalog_place_sources_provider_dataset_external_id_language_uq
                DO UPDATE SET fetched_at = EXCLUDED.fetched_at, payload_hash = EXCLUDED.payload_hash
                RETURNING id, place_id, external_id
            ), inserted_raw AS (
                INSERT INTO onmaru.catalog_kto_korean_content_versions (
                    revision_id, source_ref_id, contentid, contenttypeid, title, addr1, addr2,
                    zipcode, areacode, sigungucode, cat1, cat2, cat3, firstimage, firstimage2,
                    lcls_systm1, lcls_systm2, lcls_systm3, ldong_regn_cd, ldong_signgu_cd,
                    cpyrht_div_cd, mapx, mapy, mlevel, tel, createdtime, modifiedtime, showflag, raw_hash
                )
                SELECT p.revision_id, source.id, i.external_id, nullif(i.raw->>'contenttypeid', ''),
                       coalesce(nullif(i.raw->>'title', ''), i.external_id),
                       nullif(i.raw->>'addr1', ''), nullif(i.raw->>'addr2', ''),
                       nullif(i.raw->>'zipcode', ''), nullif(i.raw->>'areacode', ''),
                       nullif(i.raw->>'sigungucode', ''), nullif(i.raw->>'cat1', ''),
                       nullif(i.raw->>'cat2', ''), nullif(i.raw->>'cat3', ''),
                       nullif(i.raw->>'firstimage', ''), nullif(i.raw->>'firstimage2', ''),
                       nullif(i.raw->>'lclsSystm1', ''), nullif(i.raw->>'lclsSystm2', ''),
                       nullif(i.raw->>'lclsSystm3', ''),
                       coalesce(nullif(i.raw->>'lDongRegnCd', ''), nullif(i.raw->>'ldongregncd', '')),
                       coalesce(nullif(i.raw->>'lDongSignguCd', ''), nullif(i.raw->>'ldongsigngucd', '')),
                       coalesce(nullif(i.raw->>'cpyrhtDivCd', ''), nullif(i.raw->>'cpyrhtdivcd', '')),
                       CASE WHEN i.raw->>'mapx' ~ '^[+-]?[0-9]+([.][0-9]+)?$' THEN (i.raw->>'mapx')::numeric END,
                       CASE WHEN i.raw->>'mapy' ~ '^[+-]?[0-9]+([.][0-9]+)?$' THEN (i.raw->>'mapy')::numeric END,
                       nullif(i.raw->>'mlevel', ''), nullif(i.raw->>'tel', ''),
                       nullif(i.raw->>'createdtime', ''), nullif(i.raw->>'modifiedtime', ''),
                       nullif(i.raw->>'showflag', ''), i.raw_hash
                FROM input i JOIN upserted_sources source USING (external_id) CROSS JOIN params p
                RETURNING 1
            ), inserted_public_ids AS (
                INSERT INTO onmaru.catalog_place_public_ids (public_id, place_id)
                SELECT i.public_id, source.place_id
                FROM input i JOIN upserted_sources source USING (external_id)
                WHERE i.publishable
                ON CONFLICT (place_id) DO NOTHING
                RETURNING place_id
            ), inserted_places AS (
                INSERT INTO onmaru.catalog_place_versions (
                    revision_id, place_id, source_ref_id, region_id, name, category, address,
                    location, overview, visit_review_eligible, status, normalized_hash
                )
                SELECT p.revision_id, source.place_id, source.id,
                       (SELECT boundary.region_id
                        FROM onmaru.catalog_region_boundaries boundary
                        JOIN onmaru.catalog_regions region
                          ON region.id = boundary.region_id AND region.active
                        WHERE ST_Covers(boundary.geometry,
                              ST_SetSRID(ST_MakePoint(i.longitude, i.latitude), 4326))
                        ORDER BY CASE region.level WHEN 'SIGUNGU' THEN 0 ELSE 1 END
                        LIMIT 1),
                       i.name, i.category,
                       nullif(concat_ws(' ', nullif(i.raw->>'addr1', ''), nullif(i.raw->>'addr2', '')), ''),
                       ST_SetSRID(ST_MakePoint(i.longitude, i.latitude), 4326)::geography,
                       coalesce(nullif(i.raw->>'overview', ''),
                                nullif(concat_ws(' ', nullif(i.raw->>'addr1', ''), nullif(i.raw->>'addr2', '')), ''),
                                i.name),
                       true, 'ACTIVE', i.normalized_hash
                FROM input i JOIN upserted_sources source USING (external_id) CROSS JOIN params p
                WHERE i.publishable
                RETURNING place_id
            ), inserted_images AS (
                INSERT INTO onmaru.catalog_place_image_versions
                    (revision_id, place_id, position, origin_img_url, small_image_url, source_ref_id, rights_note)
                SELECT p.revision_id, source.place_id, 0, i.raw->>'firstimage',
                       nullif(i.raw->>'firstimage2', ''), source.id,
                       '한국관광공사 TourAPI 원천 표기 준수'
                FROM input i JOIN upserted_sources source USING (external_id) CROSS JOIN params p
                WHERE i.publishable AND nullif(i.raw->>'firstimage', '') IS NOT NULL
                RETURNING place_id
            ), inserted_tags AS (
                INSERT INTO onmaru.catalog_place_content_tag_versions (
                    revision_id, place_id, position, label, score, source,
                    algorithm_version, source_hash, generated_at
                )
                SELECT p.revision_id, source.place_id, 0, i.tag_label, 1, 'GENERATED',
                       i.allowlist_version, i.normalized_hash, p.fetched_at
                FROM input i JOIN upserted_sources source USING (external_id) CROSS JOIN params p
                WHERE i.publishable
                RETURNING place_id
            )
            SELECT (SELECT count(*) FROM inserted_raw),
                   (SELECT count(*) FROM inserted_places),
                   (SELECT count(*) FROM input WHERE qualification_status = 'QUARANTINED')
            """;

    private final DataSource dataSource;
    private final SourceQualificationPolicy qualificationPolicy;
    private final ObjectMapper objectMapper;

    public JdbcTourApiCatalogPublisher(DataSource dataSource) {
        this(dataSource, SourceQualificationPolicy.withDefaultAllowlist(), new ObjectMapper());
    }

    JdbcTourApiCatalogPublisher(
            DataSource dataSource,
            SourceQualificationPolicy qualificationPolicy,
            ObjectMapper objectMapper
    ) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource must not be null");
        this.qualificationPolicy = Objects.requireNonNull(qualificationPolicy, "qualificationPolicy must not be null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
    }

    public PublishSession start(Instant fetchedAt) {
        UUID revisionId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        try (var connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            createRevision(connection, revisionId, fetchedAt);
            createRun(connection, runId, revisionId, fetchedAt);
            connection.commit();
            return new PublishSession(revisionId, runId, fetchedAt);
        } catch (SQLException exception) {
            throw new IllegalStateException("failed to start TourAPI catalog staging", exception);
        }
    }

    public boolean isSyncDue(Instant now, Duration minimumInterval) {
        Objects.requireNonNull(now, "now must not be null");
        Objects.requireNonNull(minimumInterval, "minimumInterval must not be null");
        if (minimumInterval.isNegative() || minimumInterval.isZero()) {
            throw new IllegalArgumentException("minimumInterval must be positive");
        }
        try (var connection = dataSource.getConnection();
             var statement = connection.prepareStatement("""
                     SELECT max(finished_at)
                     FROM onmaru.operations_sync_runs
                     WHERE dataset = ? AND status = 'SUCCEEDED'
                     """)) {
            statement.setString(1, DATASET);
            try (var rows = statement.executeQuery()) {
                if (!rows.next()) return true;
                OffsetDateTime lastFinishedAt = rows.getObject(1, OffsetDateTime.class);
                return lastFinishedAt == null
                        || !now.isBefore(lastFinishedAt.toInstant().plus(minimumInterval));
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("failed to read TourAPI catalog sync cadence", exception);
        }
    }

    public PageResult stagePage(PublishSession session, List<SourceRecord> records) {
        Objects.requireNonNull(session);
        Objects.requireNonNull(records);
        if (records.isEmpty()) return new PageResult(0, 0, 0, 0);
        List<Map<String, Object>> rows = new ArrayList<>(records.size());
        int expectedPublished = 0;
        int expectedQuarantined = 0;
        int expectedSkipped = 0;
        for (SourceRecord record : records) {
            var result = qualificationPolicy.qualify(record);
            var row = new LinkedHashMap<String, Object>();
            String rawHash = result.candidate().map(CanonicalCandidate::normalizedHash)
                    .orElseGet(() -> result.quarantine().map(QuarantineRecord::payloadHash)
                            .orElseGet(() -> sourceHash(record)));
            String externalId = firstNonBlank(record.field("contentid"), "missing-" + rawHash.substring(0, 16));
            row.put("external_id", externalId);
            row.put("raw_hash", rawHash);
            row.put("proposed_place_id", deterministicId("place", externalId));
            row.put("proposed_source_id", deterministicId("source", externalId));
            row.put("raw", record.fields());
            row.put("qualification_status", result.status().name());
            if (result.candidate().isPresent()) {
                CanonicalCandidate candidate = result.candidate().orElseThrow();
                row.put("publishable", true);
                row.put("public_id", publicId(externalId));
                row.put("name", candidate.name());
                row.put("category", candidate.category().name());
                row.put("longitude", candidate.longitude());
                row.put("latitude", candidate.latitude());
                row.put("normalized_hash", candidate.normalizedHash());
                row.put("allowlist_version", candidate.allowlistVersion());
                row.put("tag_label", categoryLabel(candidate.category().name()));
                expectedPublished++;
            } else if (result.status() == QualificationStatus.QUARANTINED) {
                row.put("publishable", false);
                expectedQuarantined++;
            } else {
                row.put("publishable", false);
                expectedSkipped++;
            }
            rows.add(row);
        }
        try (var connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try (var statement = connection.prepareStatement(BULK_STAGE_SQL)) {
                statement.setObject(1, session.revisionId());
                statement.setObject(2, atUtc(session.fetchedAt()));
                statement.setString(3, json(rows));
                try (var result = statement.executeQuery()) {
                    if (!result.next()) throw new IllegalStateException("bulk stage returned no verification row");
                    int raw = result.getInt(1);
                    int published = result.getInt(2);
                    int quarantined = result.getInt(3);
                    if (raw != records.size() || published != expectedPublished || quarantined != expectedQuarantined) {
                        throw new IllegalStateException("bulk stage count mismatch");
                    }
                }
                connection.commit();
                return new PageResult(records.size(), expectedPublished, expectedQuarantined, expectedSkipped);
            } catch (Exception exception) {
                connection.rollback();
                throw exception;
            }
        } catch (Exception exception) {
            throw new IllegalStateException("failed to stage TourAPI catalog page", exception);
        }
    }

    private UUID deterministicId(String kind, String externalId) {
        return UUID.nameUUIDFromBytes((PROVIDER + "|" + DATASET + "|" + kind + "|" + externalId)
                .getBytes(StandardCharsets.UTF_8));
    }

    private String publicId(String externalId) {
        return "p-tourapi-" + externalId.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-|-$)", "");
    }

    public PublishResult complete(PublishSession session, int rawCount, int expectedCount,
                                  int publishedCount, int quarantinedCount, int skippedCount,
                                  Instant completedAt) {
        if (rawCount == 0 || rawCount != expectedCount) {
            throw new IllegalStateException("incomplete TourAPI snapshot: expected=" + expectedCount + ", actual=" + rawCount);
        }
        if (publishedCount == 0) {
            throw new IllegalStateException("a full TourAPI snapshot with zero qualified places requires review");
        }
        try (var connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            UUID activeRevisionId = findActiveRevision(connection);
            if (activeRevisionId != null
                    && matchesActiveSnapshot(connection, session.revisionId(), activeRevisionId)) {
                reuseActiveRevision(connection, session, activeRevisionId, completedAt,
                        rawCount, publishedCount, quarantinedCount, skippedCount);
                connection.commit();
                return new PublishResult(activeRevisionId, rawCount, publishedCount, quarantinedCount, skippedCount);
            }
            publishRevision(connection, session.revisionId(), session.runId(), completedAt,
                    rawCount, publishedCount, quarantinedCount, skippedCount);
            connection.commit();
            return new PublishResult(session.revisionId(), rawCount, publishedCount, quarantinedCount, skippedCount);
        } catch (SQLException exception) {
            throw new IllegalStateException("failed to activate TourAPI catalog snapshot", exception);
        }
    }

    private UUID findActiveRevision(Connection connection) throws SQLException {
        try (var statement = connection.prepareStatement("""
                SELECT revision_id FROM onmaru.catalog_active_datasets WHERE dataset = ?
                """)) {
            statement.setString(1, DATASET);
            try (var result = statement.executeQuery()) {
                return result.next() ? result.getObject(1, UUID.class) : null;
            }
        }
    }

    private boolean matchesActiveSnapshot(Connection connection, UUID stagedRevisionId, UUID activeRevisionId)
            throws SQLException {
        try (var statement = connection.prepareStatement("""
                SELECT NOT EXISTS (
                    (SELECT contentid, raw_hash FROM onmaru.catalog_kto_korean_content_versions
                     WHERE revision_id = ?)
                    EXCEPT
                    (SELECT contentid, raw_hash FROM onmaru.catalog_kto_korean_content_versions
                     WHERE revision_id = ?)
                ) AND NOT EXISTS (
                    (SELECT contentid, raw_hash FROM onmaru.catalog_kto_korean_content_versions
                     WHERE revision_id = ?)
                    EXCEPT
                    (SELECT contentid, raw_hash FROM onmaru.catalog_kto_korean_content_versions
                     WHERE revision_id = ?)
                )
                """)) {
            statement.setObject(1, stagedRevisionId);
            statement.setObject(2, activeRevisionId);
            statement.setObject(3, activeRevisionId);
            statement.setObject(4, stagedRevisionId);
            try (var result = statement.executeQuery()) {
                return result.next() && result.getBoolean(1);
            }
        }
    }

    private void reuseActiveRevision(
            Connection connection,
            PublishSession session,
            UUID activeRevisionId,
            Instant completedAt,
            int raw,
            int published,
            int quarantined,
            int skipped
    ) throws SQLException {
        for (String table : List.of(
                "catalog_place_content_tag_versions",
                "catalog_hanok_detail_versions",
                "catalog_place_image_versions",
                "catalog_kto_korean_info_versions",
                "catalog_kto_korean_intro_versions",
                "catalog_kto_korean_content_versions",
                "catalog_place_versions")) {
            try (var statement = connection.prepareStatement(
                    "DELETE FROM onmaru." + table + " WHERE revision_id = ?")) {
                statement.setObject(1, session.revisionId());
                statement.executeUpdate();
            }
        }
        try (var quarantine = connection.prepareStatement(
                "DELETE FROM onmaru.operations_sync_quarantine WHERE run_id = ?")) {
            quarantine.setObject(1, session.runId());
            quarantine.executeUpdate();
        }
        try (var run = connection.prepareStatement("""
                UPDATE onmaru.operations_sync_runs
                SET status = 'SUCCEEDED', finished_at = ?, revision_id = ?, counts = ?::jsonb
                WHERE id = ?
                """)) {
            run.setObject(1, atUtc(completedAt));
            run.setObject(2, activeRevisionId);
            run.setString(3, json(Map.of(
                    "raw", raw,
                    "published", published,
                    "quarantined", quarantined,
                    "skipped", skipped,
                    "unchanged", true)));
            run.setObject(4, session.runId());
            run.executeUpdate();
        }
        try (var revision = connection.prepareStatement(
                "DELETE FROM onmaru.catalog_dataset_revisions WHERE id = ?")) {
            revision.setObject(1, session.revisionId());
            revision.executeUpdate();
        }
        deleteInactiveCatalogRevisions(connection, activeRevisionId);
    }

    public void fail(PublishSession session, String errorCode, Instant failedAt) {
        try (var connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try (var run = connection.prepareStatement("""
                    UPDATE onmaru.operations_sync_runs
                    SET status = 'FAILED', finished_at = ?, error_code = ?, revision_id = NULL
                    WHERE id = ? AND status = 'RUNNING'
                    """)) {
                run.setObject(1, atUtc(failedAt));
                run.setString(2, errorCode);
                run.setObject(3, session.runId());
                run.executeUpdate();
            }
            deleteCatalogRevision(connection, session.revisionId());
            connection.commit();
        } catch (SQLException exception) {
            throw new IllegalStateException("failed to mark TourAPI catalog staging as failed", exception);
        }
    }

    public PublishResult publish(List<SourceRecord> records, Instant fetchedAt) {
        Objects.requireNonNull(records, "records must not be null");
        Objects.requireNonNull(fetchedAt, "fetchedAt must not be null");
        if (records.isEmpty()) {
            throw new IllegalArgumentException("an empty full TourAPI snapshot must not replace the active revision");
        }

        UUID revisionId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        int published = 0;
        int quarantined = 0;
        int skipped = 0;
        try (var connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                createRevision(connection, revisionId, fetchedAt);
                createRun(connection, runId, revisionId, fetchedAt);
                for (SourceRecord record : records) {
                    var result = qualificationPolicy.qualify(record);
                    String rawHash = result.candidate().map(CanonicalCandidate::normalizedHash)
                            .orElseGet(() -> result.quarantine().map(QuarantineRecord::payloadHash)
                                    .orElseGet(() -> sourceHash(record)));
                    String externalId = firstNonBlank(record.field("contentid"), "missing-" + rawHash.substring(0, 16));
                    PlaceSourceIds ids = findOrCreateSource(connection, externalId, rawHash, fetchedAt);
                    insertKtoVersion(connection, revisionId, ids.sourceId(), externalId, rawHash, record);
                    if (result.candidate().isPresent()) {
                        stageCandidate(connection, revisionId, ids, result.candidate().orElseThrow(), record, fetchedAt);
                        published++;
                    } else if (result.status() == QualificationStatus.QUARANTINED) {
                        quarantined++;
                    } else {
                        skipped++;
                    }
                }
                if (published == 0) {
                    throw new IllegalStateException("a full TourAPI snapshot with zero qualified places requires review");
                }
                publishRevision(connection, revisionId, runId, fetchedAt,
                        records.size(), published, quarantined, skipped);
                connection.commit();
                return new PublishResult(revisionId, records.size(), published, quarantined, skipped);
            } catch (Exception exception) {
                connection.rollback();
                throw exception;
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("failed to publish TourAPI catalog snapshot", exception);
        }
    }

    private void createRevision(Connection connection, UUID revisionId, Instant fetchedAt) throws SQLException {
        try (var statement = connection.prepareStatement("""
                INSERT INTO onmaru.catalog_dataset_revisions
                    (id, dataset, status, fetched_at)
                VALUES (?, ?, 'STAGING', ?)
                """)) {
            statement.setObject(1, revisionId);
            statement.setString(2, DATASET);
            statement.setObject(3, atUtc(fetchedAt));
            statement.executeUpdate();
        }
    }

    private void createRun(Connection connection, UUID runId, UUID revisionId, Instant fetchedAt) throws SQLException {
        try (var statement = connection.prepareStatement("""
                INSERT INTO onmaru.operations_sync_runs
                    (id, dataset, scheduled_for, attempt, status, revision_id, started_at)
                VALUES (?, ?, ?, 1, 'RUNNING', ?, ?)
                """)) {
            statement.setObject(1, runId);
            statement.setString(2, DATASET);
            statement.setObject(3, atUtc(fetchedAt));
            statement.setObject(4, revisionId);
            statement.setObject(5, atUtc(fetchedAt));
            statement.executeUpdate();
        }
    }

    private void stageCandidate(
            Connection connection,
            UUID revisionId,
            PlaceSourceIds ids,
            CanonicalCandidate candidate,
            SourceRecord record,
            Instant fetchedAt
    ) throws SQLException {
        ensurePublicId(connection, ids.placeId(), candidate.externalId());
        UUID regionId = resolveRegion(connection, candidate.longitude(), candidate.latitude());
        insertPlaceVersion(connection, revisionId, ids, candidate, record, regionId);
        insertImage(connection, revisionId, ids, record);
        insertCategoryTag(connection, revisionId, ids.placeId(), candidate, fetchedAt);
    }

    private PlaceSourceIds findOrCreateSource(
            Connection connection,
            String externalId,
            String rawHash,
            Instant fetchedAt
    ) throws SQLException {
        try (var select = connection.prepareStatement("""
                SELECT id, place_id
                FROM onmaru.catalog_place_sources
                WHERE provider = ? AND dataset = ? AND external_id = ? AND language = ?
                """)) {
            select.setString(1, PROVIDER);
            select.setString(2, DATASET);
            select.setString(3, externalId);
            select.setString(4, LANGUAGE);
            try (var rows = select.executeQuery()) {
                if (rows.next()) {
                    var ids = new PlaceSourceIds(
                            rows.getObject("place_id", UUID.class),
                            rows.getObject("id", UUID.class));
                    updateSource(connection, ids.sourceId(), rawHash, fetchedAt);
                    return ids;
                }
            }
        }

        UUID placeId = UUID.randomUUID();
        UUID sourceId = UUID.randomUUID();
        try (var identity = connection.prepareStatement("""
                INSERT INTO onmaru.catalog_place_identity (id, created_at) VALUES (?, ?)
                """)) {
            identity.setObject(1, placeId);
            identity.setObject(2, atUtc(fetchedAt));
            identity.executeUpdate();
        }
        try (var source = connection.prepareStatement("""
                INSERT INTO onmaru.catalog_place_sources
                    (id, place_id, provider, dataset, external_id, language, fetched_at, payload_hash)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            source.setObject(1, sourceId);
            source.setObject(2, placeId);
            source.setString(3, PROVIDER);
            source.setString(4, DATASET);
            source.setString(5, externalId);
            source.setString(6, LANGUAGE);
            source.setObject(7, atUtc(fetchedAt));
            source.setString(8, rawHash);
            source.executeUpdate();
        }
        return new PlaceSourceIds(placeId, sourceId);
    }

    private void updateSource(Connection connection, UUID sourceId, String hash, Instant fetchedAt) throws SQLException {
        try (var statement = connection.prepareStatement("""
                UPDATE onmaru.catalog_place_sources
                SET fetched_at = ?, payload_hash = ?
                WHERE id = ?
                """)) {
            statement.setObject(1, atUtc(fetchedAt));
            statement.setString(2, hash);
            statement.setObject(3, sourceId);
            statement.executeUpdate();
        }
    }

    private void ensurePublicId(Connection connection, UUID placeId, String externalId) throws SQLException {
        String publicId = "p-tourapi-" + externalId.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-|-$)", "");
        try (var statement = connection.prepareStatement("""
                INSERT INTO onmaru.catalog_place_public_ids (public_id, place_id)
                VALUES (?, ?)
                ON CONFLICT (place_id) DO NOTHING
                """)) {
            statement.setString(1, publicId);
            statement.setObject(2, placeId);
            statement.executeUpdate();
        }
    }

    private UUID resolveRegion(Connection connection, double longitude, double latitude) throws SQLException {
        try (var statement = connection.prepareStatement("""
                SELECT boundary.region_id
                FROM onmaru.catalog_region_boundaries boundary
                JOIN onmaru.catalog_regions region ON region.id = boundary.region_id AND region.active
                WHERE ST_Covers(boundary.geometry, ST_SetSRID(ST_MakePoint(?, ?), 4326))
                ORDER BY CASE region.level WHEN 'SIGUNGU' THEN 0 ELSE 1 END
                LIMIT 1
                """)) {
            statement.setDouble(1, longitude);
            statement.setDouble(2, latitude);
            try (var rows = statement.executeQuery()) {
                return rows.next() ? rows.getObject(1, UUID.class) : null;
            }
        }
    }

    private void insertPlaceVersion(
            Connection connection,
            UUID revisionId,
            PlaceSourceIds ids,
            CanonicalCandidate candidate,
            SourceRecord record,
            UUID regionId
    ) throws SQLException {
        String address = joined(record.field("addr1"), record.field("addr2"));
        String overview = firstNonBlank(record.field("overview"), address, candidate.name());
        try (var statement = connection.prepareStatement("""
                INSERT INTO onmaru.catalog_place_versions (
                    revision_id, place_id, source_ref_id, region_id, name, category, address,
                    location, overview, visit_review_eligible, status, normalized_hash
                ) VALUES (?, ?, ?, ?, ?, ?, ?,
                    ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography, ?, true, 'ACTIVE', ?)
                """)) {
            statement.setObject(1, revisionId);
            statement.setObject(2, ids.placeId());
            statement.setObject(3, ids.sourceId());
            statement.setObject(4, regionId);
            statement.setString(5, candidate.name());
            statement.setString(6, candidate.category().name());
            statement.setString(7, blankToNull(address));
            statement.setDouble(8, candidate.longitude());
            statement.setDouble(9, candidate.latitude());
            statement.setString(10, overview);
            statement.setString(11, candidate.normalizedHash());
            statement.executeUpdate();
        }
    }

    private void insertKtoVersion(
            Connection connection,
            UUID revisionId,
            UUID sourceId,
            String externalId,
            String rawHash,
            SourceRecord record
    ) throws SQLException {
        try (var statement = connection.prepareStatement("""
                INSERT INTO onmaru.catalog_kto_korean_content_versions (
                    revision_id, source_ref_id, contentid, contenttypeid, title, addr1, addr2,
                    zipcode, areacode, sigungucode, cat1, cat2, cat3, firstimage, firstimage2,
                    lcls_systm1, lcls_systm2, lcls_systm3,
                    ldong_regn_cd, ldong_signgu_cd,
                    cpyrht_div_cd, mapx, mapy, mlevel, tel, createdtime, modifiedtime, showflag, raw_hash
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            int index = 1;
            statement.setObject(index++, revisionId);
            statement.setObject(index++, sourceId);
            statement.setString(index++, externalId);
            statement.setString(index++, blankToNull(field(record, "contenttypeid")));
            statement.setString(index++, firstNonBlank(field(record, "title"), externalId));
            for (String name : List.of("addr1", "addr2", "zipcode", "areacode", "sigungucode", "cat1", "cat2", "cat3",
                    "firstimage", "firstimage2", "lclsSystm1", "lclsSystm2", "lclsSystm3")) {
                statement.setString(index++, blankToNull(field(record, name)));
            }
            statement.setString(index++, blankToNull(field(record, "lDongRegnCd", "ldongregncd")));
            statement.setString(index++, blankToNull(field(record, "lDongSignguCd", "ldongsigngucd")));
            statement.setString(index++, blankToNull(field(record, "cpyrhtDivCd", "cpyrhtdivcd")));
            statement.setBigDecimal(index++, decimal(record.field("mapx")));
            statement.setBigDecimal(index++, decimal(record.field("mapy")));
            for (String field : List.of("mlevel", "tel", "createdtime", "modifiedtime", "showflag")) {
                statement.setString(index++, blankToNull(record.field(field)));
            }
            statement.setString(index, rawHash);
            statement.executeUpdate();
        }
    }

    private void insertImage(Connection connection, UUID revisionId, PlaceSourceIds ids, SourceRecord record)
            throws SQLException {
        String image = blankToNull(record.field("firstimage"));
        if (image == null) {
            return;
        }
        try (var statement = connection.prepareStatement("""
                INSERT INTO onmaru.catalog_place_image_versions
                    (revision_id, place_id, position, origin_img_url, small_image_url, source_ref_id, rights_note)
                VALUES (?, ?, 0, ?, ?, ?, '한국관광공사 TourAPI 원천 표기 준수')
                """)) {
            statement.setObject(1, revisionId);
            statement.setObject(2, ids.placeId());
            statement.setString(3, image);
            statement.setString(4, blankToNull(record.field("firstimage2")));
            statement.setObject(5, ids.sourceId());
            statement.executeUpdate();
        }
    }

    private void insertCategoryTag(
            Connection connection,
            UUID revisionId,
            UUID placeId,
            CanonicalCandidate candidate,
            Instant generatedAt
    ) throws SQLException {
        try (var statement = connection.prepareStatement("""
                INSERT INTO onmaru.catalog_place_content_tag_versions (
                    revision_id, place_id, position, label, score, source,
                    algorithm_version, source_hash, generated_at
                ) VALUES (?, ?, 0, ?, 1, 'GENERATED', ?, ?, ?)
                """)) {
            statement.setObject(1, revisionId);
            statement.setObject(2, placeId);
            statement.setString(3, categoryLabel(candidate.category().name()));
            statement.setString(4, candidate.allowlistVersion());
            statement.setString(5, candidate.normalizedHash());
            statement.setObject(6, atUtc(generatedAt));
            statement.executeUpdate();
        }
    }

    private void publishRevision(
            Connection connection,
            UUID revisionId,
            UUID runId,
            Instant publishedAt,
            int raw,
            int published,
            int quarantined,
            int skipped
    ) throws SQLException {
        publishMapInfoProjection(connection, revisionId, publishedAt);
        try (var revision = connection.prepareStatement("""
                UPDATE onmaru.catalog_dataset_revisions
                SET status = 'PUBLISHED', published_at = ?
                WHERE id = ? AND status = 'STAGING'
                """)) {
            revision.setObject(1, atUtc(publishedAt));
            revision.setObject(2, revisionId);
            revision.executeUpdate();
        }
        try (var pointer = connection.prepareStatement("""
                INSERT INTO onmaru.catalog_active_datasets (dataset, revision_id, activated_at)
                VALUES (?, ?, ?)
                ON CONFLICT (dataset) DO UPDATE
                SET revision_id = EXCLUDED.revision_id, activated_at = EXCLUDED.activated_at
                """)) {
            pointer.setString(1, DATASET);
            pointer.setObject(2, revisionId);
            pointer.setObject(3, atUtc(publishedAt));
            pointer.executeUpdate();
        }
        try (var run = connection.prepareStatement("""
                UPDATE onmaru.operations_sync_runs
                SET status = 'SUCCEEDED', finished_at = ?, counts = ?::jsonb
                WHERE id = ?
                """)) {
            run.setObject(1, atUtc(publishedAt));
            run.setString(2, json(Map.of(
                    "raw", raw,
                    "published", published,
                    "quarantined", quarantined,
                    "skipped", skipped)));
            run.setObject(3, runId);
            run.executeUpdate();
        }
        deleteInactiveCatalogRevisions(connection, revisionId);
    }

    /**
     * Builds the information-mode read model inside the same transaction as the
     * catalog active-pointer update. The source tables remain the system of
     * record; map requests never need to read the raw TourAPI rows.
     */
    private void publishMapInfoProjection(Connection connection, UUID revisionId, Instant publishedAt)
            throws SQLException {
        try (var statement = connection.prepareStatement("""
                INSERT INTO onmaru.map_place_read_projection (
                    revision_id, place_id, public_id, name, normalized_name, status,
                    location_geom, sido_code, sigungu_code, eupmyeondong_code,
                    display_category, thumbnail_url, summary, sort_key
                )
                SELECT version.revision_id,
                       version.place_id,
                       public_id.public_id,
                       version.name,
                       lower(btrim(version.name)),
                       version.status,
                       version.location::geometry,
                       COALESCE(parent.code, raw.ldong_regn_cd),
                       CASE
                           WHEN region.level = 'SIGUNGU' THEN region.code
                           ELSE NULLIF(concat_ws(':', raw.ldong_regn_cd, raw.ldong_signgu_cd), ':')
                       END,
                       NULL,
                       version.category,
                       image.origin_img_url,
                       version.overview,
                       lower(btrim(version.name)) || '|' || public_id.public_id
                FROM onmaru.catalog_place_versions version
                JOIN onmaru.catalog_place_public_ids public_id
                  ON public_id.place_id = version.place_id
                LEFT JOIN onmaru.catalog_regions region
                  ON region.id = version.region_id AND region.active
                LEFT JOIN onmaru.catalog_regions parent
                  ON parent.id = region.parent_id AND parent.active
                LEFT JOIN onmaru.catalog_place_sources source
                  ON source.id = version.source_ref_id
                LEFT JOIN onmaru.catalog_kto_korean_content_versions raw
                  ON raw.revision_id = version.revision_id
                 AND raw.source_ref_id = version.source_ref_id
                LEFT JOIN onmaru.catalog_place_image_versions image
                  ON image.revision_id = version.revision_id
                 AND image.place_id = version.place_id
                 AND image.position = 0
                WHERE version.revision_id = ?
                  AND version.status = 'ACTIVE'
                  AND version.location IS NOT NULL
                  AND COALESCE(
                        parent.code,
                        raw.ldong_regn_cd,
                        CASE WHEN region.level = 'SIDO' THEN region.code END
                      ) IS NOT NULL
                """)) {
            statement.setObject(1, revisionId);
            statement.executeUpdate();
        }

        validateMapPlaceProjection(connection, revisionId);

        try (var statement = connection.prepareStatement("""
                INSERT INTO onmaru.map_place_category_projection
                    (revision_id, place_id, canonical_category)
                SELECT revision_id, place_id, display_category
                FROM onmaru.map_place_read_projection
                WHERE revision_id = ?
                ON CONFLICT (revision_id, place_id, canonical_category) DO NOTHING
                """)) {
            statement.setObject(1, revisionId);
            statement.executeUpdate();
        }

        try (var statement = connection.prepareStatement("""
                WITH scoped_places AS (
                    SELECT projection.revision_id,
                           'DISTRICT'::varchar AS scope_type,
                           projection.sigungu_code AS region_code,
                           projection.place_id,
                           category.canonical_category
                    FROM onmaru.map_place_read_projection projection
                    JOIN onmaru.map_place_category_projection category
                      ON category.revision_id = projection.revision_id
                     AND category.place_id = projection.place_id
                    WHERE projection.revision_id = ?
                      AND projection.sigungu_code IS NOT NULL
                    UNION ALL
                    SELECT projection.revision_id,
                           'REGION'::varchar AS scope_type,
                           projection.sido_code AS region_code,
                           projection.place_id,
                           category.canonical_category
                    FROM onmaru.map_place_read_projection projection
                    JOIN onmaru.map_place_category_projection category
                      ON category.revision_id = projection.revision_id
                     AND category.place_id = projection.place_id
                    WHERE projection.revision_id = ?
                      AND projection.sido_code IS NOT NULL
                ), grouped AS (
                    SELECT revision_id, scope_type, region_code,
                           canonical_category, count(DISTINCT place_id)::integer AS place_count
                    FROM scoped_places
                    GROUP BY revision_id, scope_type, region_code, canonical_category
                )
                INSERT INTO onmaru.map_scope_count_projection
                    (revision_id, scope_type, region_code, canonical_category, place_count)
                SELECT revision_id, scope_type, region_code, canonical_category, place_count
                FROM grouped
                ON CONFLICT (revision_id, scope_type, region_code, canonical_category)
                DO UPDATE SET place_count = EXCLUDED.place_count
                """)) {
            statement.setObject(1, revisionId);
            statement.setObject(2, revisionId);
            statement.executeUpdate();
        }

        String checksum;
        int rowCount;
        try (var statement = connection.prepareStatement("""
                SELECT count(*)::integer,
                       encode(
                           digest(
                               coalesce(
                                   string_agg(
                                       place_id::text || '|' || public_id || '|' || name || '|' ||
                                       display_category || '|' || ST_AsEWKT(location_geom),
                                       E'\\n' ORDER BY place_id
                                   ),
                                   ''
                               ),
                               'sha256'
                           ),
                           'hex'
                       )
                FROM onmaru.map_place_read_projection
                WHERE revision_id = ?
                """)) {
            statement.setObject(1, revisionId);
            try (var rows = statement.executeQuery()) {
                if (!rows.next()) throw new SQLException("map projection checksum query returned no row");
                rowCount = rows.getInt(1);
                checksum = rows.getString(2);
            }
        }

        try (var statement = connection.prepareStatement("""
                INSERT INTO onmaru.map_projection_publications
                    (revision_id, projection_name, mapping_version, row_count, checksum, published_at, status)
                VALUES (?, 'map-info', 'map-category-v1', ?, ?, ?, 'PUBLISHED')
                """)) {
            statement.setObject(1, revisionId);
            statement.setInt(2, rowCount);
            statement.setString(3, checksum);
            statement.setObject(4, atUtc(publishedAt));
            statement.executeUpdate();
        }
    }

    private void validateMapPlaceProjection(Connection connection, UUID revisionId) throws SQLException {
        try (var statement = connection.prepareStatement("""
                SELECT
                    (SELECT count(*)
                     FROM onmaru.catalog_place_versions
                     WHERE revision_id = ? AND status = 'ACTIVE') AS source_count,
                    (SELECT count(*)
                     FROM onmaru.map_place_read_projection
                     WHERE revision_id = ?) AS projection_count,
                    (SELECT count(*)
                     FROM onmaru.catalog_place_versions version
                     LEFT JOIN onmaru.catalog_place_public_ids public_id
                       ON public_id.place_id = version.place_id
                     LEFT JOIN onmaru.catalog_place_sources source
                       ON source.id = version.source_ref_id
                     LEFT JOIN onmaru.catalog_kto_korean_content_versions raw
                       ON raw.revision_id = version.revision_id
                      AND raw.source_ref_id = version.source_ref_id
                     LEFT JOIN onmaru.catalog_regions region
                       ON region.id = version.region_id AND region.active
                     LEFT JOIN onmaru.catalog_regions parent
                       ON parent.id = region.parent_id AND parent.active
                     WHERE version.revision_id = ?
                       AND version.status = 'ACTIVE'
                       AND (version.location IS NULL OR public_id.public_id IS NULL OR
                            COALESCE(parent.code, raw.ldong_regn_cd,
                                     CASE WHEN region.level = 'SIDO' THEN region.code END) IS NULL)
                    ) AS invalid_count
                """)) {
            statement.setObject(1, revisionId);
            statement.setObject(2, revisionId);
            statement.setObject(3, revisionId);
            try (var rows = statement.executeQuery()) {
                if (!rows.next()) throw new SQLException("map projection validation returned no row");
                int sourceCount = rows.getInt("source_count");
                int projectionCount = rows.getInt("projection_count");
                int invalidCount = rows.getInt("invalid_count");
                if (invalidCount != 0 || sourceCount != projectionCount) {
                    throw new SQLException("map projection validation failed: source=" + sourceCount
                            + ", projection=" + projectionCount + ", invalid=" + invalidCount);
                }
            }
        }
    }

    private void deleteInactiveCatalogRevisions(Connection connection, UUID activeRevisionId) throws SQLException {
        var revisionIds = new ArrayList<UUID>();
        try (var statement = connection.prepareStatement("""
                SELECT id
                FROM onmaru.catalog_dataset_revisions
                WHERE dataset = ? AND id <> ?
                FOR UPDATE
                """)) {
            statement.setString(1, DATASET);
            statement.setObject(2, activeRevisionId);
            try (var rows = statement.executeQuery()) {
                while (rows.next()) revisionIds.add(rows.getObject(1, UUID.class));
            }
        }
        for (var revisionId : revisionIds) deleteCatalogRevision(connection, revisionId);
    }

    private void deleteCatalogRevision(Connection connection, UUID revisionId) throws SQLException {
        try (var quarantine = connection.prepareStatement("""
                DELETE FROM onmaru.operations_sync_quarantine quarantine
                USING onmaru.operations_sync_runs run
                WHERE quarantine.run_id = run.id AND run.revision_id = ?
                """)) {
            quarantine.setObject(1, revisionId);
            quarantine.executeUpdate();
        }
        try (var runs = connection.prepareStatement("""
                UPDATE onmaru.operations_sync_runs SET revision_id = NULL WHERE revision_id = ?
                """)) {
            runs.setObject(1, revisionId);
            runs.executeUpdate();
        }
        try (var children = connection.prepareStatement("""
                UPDATE onmaru.catalog_dataset_revisions SET base_revision_id = NULL WHERE base_revision_id = ?
                """)) {
            children.setObject(1, revisionId);
            children.executeUpdate();
        }
        for (String table : List.of(
                "catalog_place_content_tag_versions",
                "catalog_hanok_detail_versions",
                "catalog_place_image_versions",
                "catalog_kto_korean_info_versions",
                "catalog_kto_korean_intro_versions",
                "catalog_kto_korean_content_versions",
                "catalog_place_versions")) {
            try (var statement = connection.prepareStatement(
                    "DELETE FROM onmaru." + table + " WHERE revision_id = ?")) {
                statement.setObject(1, revisionId);
                statement.executeUpdate();
            }
        }
        try (var revision = connection.prepareStatement("""
                DELETE FROM onmaru.catalog_dataset_revisions WHERE id = ?
                """)) {
            revision.setObject(1, revisionId);
            revision.executeUpdate();
        }
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("failed to serialize catalog sync metadata", exception);
        }
    }

    private String sourceHash(SourceRecord record) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(json(new TreeMap<>(record.fields())).getBytes(StandardCharsets.UTF_8));
            StringBuilder value = new StringBuilder(hash.length * 2);
            for (byte next : hash) value.append(String.format("%02x", next));
            return value.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required by the Java runtime", exception);
        }
    }

    private OffsetDateTime atUtc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }

    private BigDecimal decimal(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return new BigDecimal(value);
        } catch (NumberFormatException ignored) {
            // The raw snapshot must retain malformed provider rows so they can be
            // quarantined and audited instead of aborting the complete dataset.
            return null;
        }
    }

    private String joined(String first, String second) {
        return List.of(firstNonBlank(first, ""), firstNonBlank(second, ""))
                .stream().filter(value -> !value.isBlank()).reduce((left, right) -> left + " " + right).orElse("");
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.strip();
            }
        }
        return "";
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private String field(SourceRecord record, String... names) {
        for (String name : names) {
            String value = record.field(name);
            if (value != null) return value;
        }
        return null;
    }

    private String categoryLabel(String category) {
        return switch (category) {
            case "HANOK" -> "한옥";
            case "HANOK_STAY" -> "한옥숙박";
            case "HANOK_CAFE" -> "한옥카페";
            case "HANOK_EXPERIENCE" -> "전통체험";
            case "TRADITIONAL_MARKET" -> "전통시장";
            case "HISTORIC_SITE" -> "역사문화";
            case "NATURE_SITE" -> "자연";
            case "CULTURE_ART" -> "문화예술";
            case "TRADITIONAL_FOOD" -> "전통음식";
            case "GARDEN_ECOLOGY" -> "정원생태";
            case "LOCAL_SCENE" -> "지역체험";
            case "LEISURE_ACTIVITY" -> "레저활동";
            default -> category;
        };
    }

    private record PlaceSourceIds(UUID placeId, UUID sourceId) {
    }

    public record PublishSession(UUID revisionId, UUID runId, Instant fetchedAt) {
    }

    public record PageResult(int rawCount, int publishedCount, int quarantinedCount, int skippedCount) {
    }

    public record PublishResult(
            UUID revisionId,
            int rawCount,
            int publishedCount,
            int quarantinedCount,
            int skippedCount
    ) {
    }
}
