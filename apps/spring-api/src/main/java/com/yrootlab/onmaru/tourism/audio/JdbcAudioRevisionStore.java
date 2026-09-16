package com.yrootlab.onmaru.tourism.audio;

import com.yrootlab.onmaru.audio.sync.ActiveAudioRevision;
import com.yrootlab.onmaru.audio.sync.AudioRevisionSnapshot;
import com.yrootlab.onmaru.audio.sync.AudioRevisionStage;
import com.yrootlab.onmaru.audio.sync.AudioRevisionStore;
import com.yrootlab.onmaru.audio.sync.AudioStageCompletion;
import com.yrootlab.onmaru.audio.sync.AudioStatus;
import com.yrootlab.onmaru.audio.sync.AudioSubtitleLine;
import com.yrootlab.onmaru.audio.sync.OdiiMappedStory;
import com.yrootlab.onmaru.audio.sync.OdiiSpotIdentity;
import com.yrootlab.onmaru.audio.sync.OdiiSpotVersion;
import com.yrootlab.onmaru.audio.sync.OdiiStoryIdentity;
import com.yrootlab.onmaru.audio.sync.OdiiStoryVersion;
import com.yrootlab.onmaru.audio.sync.SubtitleTimingMode;
import com.yrootlab.onmaru.audio.sync.TranscriptProvenance;
import com.yrootlab.onmaru.catalog.application.publication.PublicationPlan;
import com.yrootlab.onmaru.catalog.application.publication.PublicationStatus;
import com.yrootlab.onmaru.catalog.application.publication.StageValidation;
import com.yrootlab.onmaru.catalog.application.sync.SyncRunLease;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public final class JdbcAudioRevisionStore implements AudioRevisionStore {

    private final DataSource dataSource;

    public JdbcAudioRevisionStore(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    public UUID initializeDataset(String dataset, Instant initializedAt) {
        return transaction(connection -> {
            UUID active = findActiveRevision(connection, dataset, false);
            if (active != null) {
                return active;
            }
            UUID revisionId = UUID.nameUUIDFromBytes(
                    (dataset + ":bootstrap").getBytes(StandardCharsets.UTF_8));
            execute(connection, """
                    INSERT INTO onmaru.catalog_dataset_revisions (
                        id, dataset, status, source_observed_at, fetched_at, published_at
                    ) VALUES (?, ?, 'PUBLISHED', ?, ?, ?)
                    ON CONFLICT (id) DO NOTHING
                    """, statement -> {
                statement.setObject(1, revisionId);
                statement.setString(2, dataset);
                setInstant(statement, 3, initializedAt);
                setInstant(statement, 4, initializedAt);
                setInstant(statement, 5, initializedAt);
            });
            execute(connection, """
                    INSERT INTO onmaru.catalog_active_datasets (dataset, revision_id, activated_at)
                    VALUES (?, ?, ?)
                    ON CONFLICT (dataset) DO NOTHING
                    """, statement -> {
                statement.setString(1, dataset);
                statement.setObject(2, revisionId);
                setInstant(statement, 3, initializedAt);
            });
            return Objects.requireNonNull(findActiveRevision(connection, dataset, false));
        });
    }

    @Override
    public AudioRevisionStage openStage(
            String dataset,
            UUID expectedBaseRevisionId,
            Instant observedAt
    ) {
        UUID revisionId = UUID.randomUUID();
        transaction(connection -> {
            UUID persistedBase = revisionExists(connection, expectedBaseRevisionId)
                    ? expectedBaseRevisionId
                    : null;
            execute(connection, """
                    INSERT INTO onmaru.catalog_dataset_revisions (
                        id, dataset, status, base_revision_id, source_observed_at, fetched_at
                    ) VALUES (?, ?, 'STAGING', ?, ?, ?)
                    """, statement -> {
                statement.setObject(1, revisionId);
                statement.setString(2, dataset);
                statement.setObject(3, persistedBase);
                setInstant(statement, 4, observedAt);
                setInstant(statement, 5, observedAt);
            });
            execute(connection, """
                    INSERT INTO onmaru.audio_revision_stages (revision_id)
                    VALUES (?)
                    """, statement -> statement.setObject(1, revisionId));
            if (persistedBase != null) {
                copyBaseRevision(connection, persistedBase, revisionId);
            }
            return null;
        });
        return new AudioRevisionStage(revisionId, expectedBaseRevisionId);
    }

    @Override
    public void stage(UUID revisionId, List<OdiiMappedStory> stories) {
        transaction(connection -> {
            requireOpenStage(connection, revisionId);
            for (OdiiMappedStory mapped : stories) {
                upsertMappedStory(connection, revisionId, mapped);
            }
            return null;
        });
    }

    @Override
    public AudioStageCompletion completeStage(
            UUID revisionId,
            int missingObservationThreshold,
            boolean emptyFullSyncReviewed
    ) {
        if (missingObservationThreshold < 1) {
            throw new IllegalArgumentException("missingObservationThreshold must be positive");
        }
        return transaction(connection -> {
            requireOpenStage(connection, revisionId);
            long storyTombstones = tombstoneMissing(
                    connection, "audio_story_versions", revisionId, missingObservationThreshold);
            long spotTombstones = tombstoneMissing(
                    connection, "audio_spot_versions", revisionId, missingObservationThreshold);
            long rowCount = count(connection, """
                    SELECT COUNT(*) FROM onmaru.audio_story_versions
                    WHERE revision_id = ? AND observed
                    """, revisionId);
            long tombstones = storyTombstones + spotTombstones;
            execute(connection, """
                    UPDATE onmaru.audio_revision_stages
                    SET ready = true,
                        row_count = ?,
                        empty_full_sync_reviewed = ?,
                        failure_code = NULL,
                        tombstone_count = ?
                    WHERE revision_id = ?
                    """, statement -> {
                statement.setLong(1, rowCount);
                statement.setBoolean(2, emptyFullSyncReviewed);
                statement.setLong(3, tombstones);
                statement.setObject(4, revisionId);
            });
            execute(connection, """
                    UPDATE onmaru.catalog_dataset_revisions SET status = 'READY' WHERE id = ?
                    """, statement -> statement.setObject(1, revisionId));
            return new AudioStageCompletion(rowCount, tombstones);
        });
    }

    @Override
    public void failStage(UUID revisionId, String failureCode) {
        transaction(connection -> {
            execute(connection, """
                    UPDATE onmaru.audio_revision_stages
                    SET ready = false, failure_code = ?
                    WHERE revision_id = ?
                    """, statement -> {
                statement.setString(1, failureCode);
                statement.setObject(2, revisionId);
            });
            execute(connection, """
                    UPDATE onmaru.catalog_dataset_revisions SET status = 'FAILED' WHERE id = ?
                    """, statement -> statement.setObject(1, revisionId));
            return null;
        });
    }

    @Override
    public long stagedItemCount(UUID revisionId) {
        return query(connection -> count(connection, """
                SELECT
                    (SELECT COUNT(*) FROM onmaru.audio_spot_versions WHERE revision_id = ?)
                    +
                    (SELECT COUNT(*) FROM onmaru.audio_story_versions WHERE revision_id = ?)
                """, revisionId, revisionId));
    }

    @Override
    public Optional<StageValidation> stageValidation(UUID revisionId) {
        return query(connection -> {
            try (var statement = connection.prepareStatement("""
                    SELECT ready, row_count, empty_full_sync_reviewed, failure_code
                    FROM onmaru.audio_revision_stages
                    WHERE revision_id = ?
                    """)) {
                statement.setObject(1, revisionId);
                try (var result = statement.executeQuery()) {
                    if (!result.next()) {
                        return Optional.empty();
                    }
                    return Optional.of(new StageValidation(
                            result.getBoolean("ready"),
                            result.getLong("row_count"),
                            result.getBoolean("empty_full_sync_reviewed"),
                            result.getString("failure_code")));
                }
            }
        });
    }

    @Override
    public PublicationStatus publishIfLeaseAndBaseRevisionMatch(
            SyncRunLease lease,
            PublicationPlan plan,
            Instant publishedAt
    ) {
        return transaction(connection -> {
            if (!leaseMatches(connection, lease, publishedAt)) {
                return PublicationStatus.LEASE_LOST;
            }
            UUID activeRevision = findActiveRevision(connection, lease.dataset(), true);
            if (!Objects.equals(activeRevision, plan.expectedActiveRevisionId())) {
                return PublicationStatus.ACTIVE_REVISION_CHANGED;
            }
            if (!stageReady(connection, plan.revisionId())) {
                return PublicationStatus.STAGE_INCOMPLETE;
            }
            execute(connection, """
                    UPDATE onmaru.catalog_active_datasets
                    SET revision_id = ?, activated_at = ?
                    WHERE dataset = ? AND revision_id = ?
                    """, statement -> {
                statement.setObject(1, plan.revisionId());
                setInstant(statement, 2, publishedAt);
                statement.setString(3, lease.dataset());
                statement.setObject(4, plan.expectedActiveRevisionId());
            });
            execute(connection, """
                    UPDATE onmaru.catalog_dataset_revisions
                    SET status = 'PUBLISHED', published_at = ?
                    WHERE id = ?
                    """, statement -> {
                setInstant(statement, 1, publishedAt);
                statement.setObject(2, plan.revisionId());
            });
            execute(connection, """
                    INSERT INTO onmaru.operations_sync_watermarks (
                        dataset, source_modified_at, external_id, last_full_success_at,
                        last_success_at, revision_id
                    ) VALUES (?, ?, ?, ?, ?, ?)
                    ON CONFLICT (dataset) DO UPDATE SET
                        source_modified_at = EXCLUDED.source_modified_at,
                        external_id = EXCLUDED.external_id,
                        last_full_success_at = EXCLUDED.last_full_success_at,
                        last_success_at = EXCLUDED.last_success_at,
                        revision_id = EXCLUDED.revision_id
                    """, statement -> {
                statement.setString(1, lease.dataset());
                statement.setString(2, plan.watermark().sourceModifiedAt());
                statement.setString(3, plan.watermark().externalId());
                setInstant(statement, 4, plan.watermark().lastSuccessAt());
                setInstant(statement, 5, plan.watermark().lastSuccessAt());
                statement.setObject(6, plan.revisionId());
            });
            return PublicationStatus.PUBLISHED;
        });
    }

    @Override
    public UUID activeRevision(String dataset) {
        return query(connection -> findActiveRevision(connection, dataset, false));
    }

    @Override
    public AudioRevisionSnapshot activeSnapshot() {
        return query(connection -> {
            try (var statement = connection.prepareStatement("""
                    SELECT revision_id FROM onmaru.catalog_active_datasets
                    ORDER BY activated_at DESC LIMIT 1
                    """)) {
                try (var result = statement.executeQuery()) {
                    return result.next()
                            ? loadSnapshot(connection, result.getObject(1, UUID.class))
                            : AudioRevisionSnapshot.empty();
                }
            }
        });
    }

    @Override
    public ActiveAudioRevision activePublishedRevision(String dataset) {
        return transaction(Connection.TRANSACTION_REPEATABLE_READ, connection -> {
            UUID revisionId = findActiveRevision(connection, dataset, false);
            if (revisionId == null) {
                throw new IllegalStateException("active audio revision is unavailable");
            }
            return new ActiveAudioRevision(revisionId, loadSnapshot(connection, revisionId));
        });
    }

    private void copyBaseRevision(Connection connection, UUID baseRevision, UUID revisionId) throws SQLException {
        execute(connection, """
                INSERT INTO onmaru.audio_spot_versions (
                    revision_id, spot_id, title, address, location, status, hash,
                    source_modified_at, observed, missing_observations
                )
                SELECT ?, spot_id, title, address, location, status, hash,
                       source_modified_at, false, missing_observations
                FROM onmaru.audio_spot_versions WHERE revision_id = ?
                """, statement -> {
            statement.setObject(1, revisionId);
            statement.setObject(2, baseRevision);
        });
        execute(connection, """
                INSERT INTO onmaru.audio_story_versions (
                    revision_id, story_id, spot_id, title, script, audio_url, image_url,
                    duration_seconds, status, hash, transcript_provenance,
                    source_modified_at, observed, missing_observations
                )
                SELECT ?, story_id, spot_id, title, script, audio_url, image_url,
                       duration_seconds, status, hash, transcript_provenance,
                       source_modified_at, false, missing_observations
                FROM onmaru.audio_story_versions WHERE revision_id = ?
                """, statement -> {
            statement.setObject(1, revisionId);
            statement.setObject(2, baseRevision);
        });
        execute(connection, """
                INSERT INTO onmaru.audio_subtitle_lines (
                    revision_id, story_id, position, text, start_seconds, timing_mode
                )
                SELECT ?, story_id, position, text, start_seconds, timing_mode
                FROM onmaru.audio_subtitle_lines WHERE revision_id = ?
                """, statement -> {
            statement.setObject(1, revisionId);
            statement.setObject(2, baseRevision);
        });
    }

    private void upsertMappedStory(
            Connection connection,
            UUID revisionId,
            OdiiMappedStory mapped
    ) throws SQLException {
        UUID requestedSpotId = stableId("spot", mapped.spot().identity());
        UUID requestedStoryId = stableId("story", mapped.story().identity());
        var spotIdentity = mapped.spot().identity();
        execute(connection, """
                INSERT INTO onmaru.audio_odii_spots (
                    id, provider, tid, tlid, lang_code, created_at
                ) VALUES (?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
                ON CONFLICT (provider, tid, tlid) DO NOTHING
                """, statement -> {
            statement.setObject(1, requestedSpotId);
            statement.setString(2, spotIdentity.provider());
            statement.setString(3, spotIdentity.tid());
            statement.setString(4, spotIdentity.tlid());
            statement.setString(5, spotIdentity.langCode());
        });
        UUID spotId = identityId(connection, "audio_odii_spots", "provider", spotIdentity.provider(),
                "tid", spotIdentity.tid(), "tlid", spotIdentity.tlid());
        var storyIdentity = mapped.story().identity();
        UUID finalSpotId = spotId;
        execute(connection, """
                INSERT INTO onmaru.audio_odii_stories (
                    id, spot_id, provider, stid, stlid, lang_code, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
                ON CONFLICT (provider, stid, stlid) DO NOTHING
                """, statement -> {
            statement.setObject(1, requestedStoryId);
            statement.setObject(2, finalSpotId);
            statement.setString(3, storyIdentity.provider());
            statement.setString(4, storyIdentity.stid());
            statement.setString(5, storyIdentity.stlid());
            statement.setString(6, storyIdentity.langCode());
        });
        UUID storyId = identityId(connection, "audio_odii_stories", "provider", storyIdentity.provider(),
                "stid", storyIdentity.stid(), "stlid", storyIdentity.stlid());
        UUID finalStoryId = storyId;
        execute(connection, """
                INSERT INTO onmaru.audio_spot_versions (
                    revision_id, spot_id, title, location, status, hash,
                    source_modified_at, observed, missing_observations
                ) VALUES (
                    ?, ?, ?, ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography,
                    ?::onmaru.audio_status, ?, ?, true, 0
                )
                ON CONFLICT (revision_id, spot_id) DO UPDATE SET
                    title = EXCLUDED.title,
                    location = EXCLUDED.location,
                    status = EXCLUDED.status,
                    hash = EXCLUDED.hash,
                    source_modified_at = EXCLUDED.source_modified_at,
                    observed = true,
                    missing_observations = 0
                """, statement -> {
            statement.setObject(1, revisionId);
            statement.setObject(2, finalSpotId);
            statement.setString(3, mapped.spot().title());
            statement.setBigDecimal(4, mapped.spot().longitude());
            statement.setBigDecimal(5, mapped.spot().latitude());
            statement.setString(6, mapped.spot().status().name());
            statement.setString(7, mapped.spot().contentHash());
            setInstant(statement, 8, mapped.spot().sourceModifiedAt());
        });
        execute(connection, """
                INSERT INTO onmaru.audio_story_versions (
                    revision_id, story_id, spot_id, title, script, audio_url, image_url,
                    duration_seconds, status, hash, transcript_provenance,
                    source_modified_at, observed, missing_observations
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?::onmaru.audio_status, ?, ?, ?, true, 0)
                ON CONFLICT (revision_id, story_id) DO UPDATE SET
                    spot_id = EXCLUDED.spot_id,
                    title = EXCLUDED.title,
                    script = EXCLUDED.script,
                    audio_url = EXCLUDED.audio_url,
                    image_url = EXCLUDED.image_url,
                    duration_seconds = EXCLUDED.duration_seconds,
                    status = EXCLUDED.status,
                    hash = EXCLUDED.hash,
                    transcript_provenance = EXCLUDED.transcript_provenance,
                    source_modified_at = EXCLUDED.source_modified_at,
                    observed = true,
                    missing_observations = 0
                """, statement -> {
            statement.setObject(1, revisionId);
            statement.setObject(2, finalStoryId);
            statement.setObject(3, finalSpotId);
            statement.setString(4, mapped.story().title());
            statement.setString(5, mapped.story().script());
            statement.setString(6, mapped.story().audioUrl());
            statement.setString(7, mapped.story().imageUrl());
            if (mapped.story().durationSeconds() == null) {
                statement.setNull(8, java.sql.Types.INTEGER);
            } else {
                statement.setInt(8, mapped.story().durationSeconds());
            }
            statement.setString(9, mapped.story().status().name());
            statement.setString(10, mapped.story().contentHash());
            statement.setString(11, mapped.story().transcriptProvenance().name());
            setInstant(statement, 12, mapped.story().sourceModifiedAt());
        });
        execute(connection, """
                DELETE FROM onmaru.audio_subtitle_lines WHERE revision_id = ? AND story_id = ?
                """, statement -> {
            statement.setObject(1, revisionId);
            statement.setObject(2, finalStoryId);
        });
        for (AudioSubtitleLine line : mapped.story().subtitleLines()) {
            execute(connection, """
                    INSERT INTO onmaru.audio_subtitle_lines (
                        revision_id, story_id, position, text, start_seconds, timing_mode
                    ) VALUES (?, ?, ?, ?, ?, ?)
                    """, statement -> {
                statement.setObject(1, revisionId);
                statement.setObject(2, finalStoryId);
                statement.setInt(3, line.position());
                statement.setString(4, line.text());
                statement.setBigDecimal(5, line.startSeconds());
                statement.setString(6, line.timingMode().name());
            });
        }
    }

    private AudioRevisionSnapshot loadSnapshot(Connection connection, UUID revisionId) throws SQLException {
        List<OdiiSpotVersion> spots = new ArrayList<>();
        Map<UUID, OdiiSpotIdentity> spotIdentities = new HashMap<>();
        try (var statement = connection.prepareStatement("""
                SELECT identity.id, identity.provider, identity.tid, identity.tlid, identity.lang_code,
                       version.title, ST_X(version.location::geometry) AS longitude,
                       ST_Y(version.location::geometry) AS latitude,
                       version.source_modified_at, version.status, version.hash
                FROM onmaru.audio_spot_versions version
                JOIN onmaru.audio_odii_spots identity ON identity.id = version.spot_id
                WHERE version.revision_id = ?
                ORDER BY identity.provider, identity.tid, identity.tlid
                """)) {
            statement.setObject(1, revisionId);
            try (var result = statement.executeQuery()) {
                while (result.next()) {
                    var identity = new OdiiSpotIdentity(
                            result.getString("provider"), result.getString("tid"),
                            result.getString("tlid"), result.getString("lang_code"));
                    spotIdentities.put(result.getObject("id", UUID.class), identity);
                    spots.add(new OdiiSpotVersion(
                            identity,
                            result.getString("title"),
                            result.getBigDecimal("longitude"),
                            result.getBigDecimal("latitude"),
                            instant(result, "source_modified_at"),
                            AudioStatus.valueOf(result.getString("status")),
                            result.getString("hash")));
                }
            }
        }
        Map<UUID, List<AudioSubtitleLine>> subtitleLines = subtitleLines(connection, revisionId);
        List<OdiiMappedStory> mappedStories = new ArrayList<>();
        try (var statement = connection.prepareStatement("""
                SELECT identity.id, identity.spot_id, identity.provider, identity.stid,
                       identity.stlid, identity.lang_code, version.title, version.script,
                       version.audio_url, version.image_url, version.duration_seconds,
                       version.source_modified_at, version.status, version.hash,
                       version.transcript_provenance
                FROM onmaru.audio_story_versions version
                JOIN onmaru.audio_odii_stories identity ON identity.id = version.story_id
                WHERE version.revision_id = ?
                ORDER BY identity.provider, identity.stid, identity.stlid
                """)) {
            statement.setObject(1, revisionId);
            try (var result = statement.executeQuery()) {
                while (result.next()) {
                    UUID storyId = result.getObject("id", UUID.class);
                    OdiiSpotIdentity spotIdentity = spotIdentities.get(result.getObject("spot_id", UUID.class));
                    if (spotIdentity == null) {
                        continue;
                    }
                    OdiiSpotVersion spot = spots.stream()
                            .filter(candidate -> candidate.identity().equals(spotIdentity))
                            .findFirst()
                            .orElseThrow();
                    Integer duration = (Integer) result.getObject("duration_seconds");
                    var story = new OdiiStoryVersion(
                            new OdiiStoryIdentity(
                                    result.getString("provider"), result.getString("stid"),
                                    result.getString("stlid"), result.getString("lang_code")),
                            spotIdentity,
                            result.getString("title"),
                            result.getString("script"),
                            TranscriptProvenance.valueOf(result.getString("transcript_provenance")),
                            result.getString("audio_url"),
                            result.getString("image_url"),
                            duration,
                            instant(result, "source_modified_at"),
                            AudioStatus.valueOf(result.getString("status")),
                            List.of(),
                            null,
                            result.getString("hash"),
                            subtitleLines.getOrDefault(storyId, List.of()));
                    mappedStories.add(new OdiiMappedStory(spot, story));
                }
            }
        }
        return AudioRevisionSnapshot.from(mappedStories);
    }

    private Map<UUID, List<AudioSubtitleLine>> subtitleLines(
            Connection connection,
            UUID revisionId
    ) throws SQLException {
        Map<UUID, List<AudioSubtitleLine>> lines = new HashMap<>();
        try (var statement = connection.prepareStatement("""
                SELECT story_id, position, text, start_seconds, timing_mode
                FROM onmaru.audio_subtitle_lines
                WHERE revision_id = ?
                ORDER BY story_id, position
                """)) {
            statement.setObject(1, revisionId);
            try (var result = statement.executeQuery()) {
                while (result.next()) {
                    lines.computeIfAbsent(result.getObject("story_id", UUID.class), ignored -> new ArrayList<>())
                            .add(new AudioSubtitleLine(
                                    result.getInt("position"),
                                    result.getBigDecimal("start_seconds"),
                                    result.getString("text"),
                                    SubtitleTimingMode.valueOf(result.getString("timing_mode"))));
                }
            }
        }
        return lines;
    }

    private long tombstoneMissing(
            Connection connection,
            String table,
            UUID revisionId,
            int threshold
    ) throws SQLException {
        String sql = """
                UPDATE onmaru.%s
                SET missing_observations = missing_observations + 1,
                    status = CASE
                        WHEN missing_observations + 1 >= ? THEN 'DELETED'::onmaru.audio_status
                        ELSE status
                    END,
                    hash = CASE
                        WHEN missing_observations + 1 >= ? AND status <> 'DELETED'
                            THEN 'deleted:' || hash
                        ELSE hash
                    END
                WHERE revision_id = ? AND NOT observed AND status = 'ACTIVE'
                """.formatted(table);
        try (var statement = connection.prepareStatement(sql)) {
            statement.setInt(1, threshold);
            statement.setInt(2, threshold);
            statement.setObject(3, revisionId);
            statement.executeUpdate();
        }
        return count(connection, """
                SELECT COUNT(*) FROM onmaru.%s
                WHERE revision_id = ? AND NOT observed AND status = 'DELETED'
                """.formatted(table), revisionId);
    }

    private void requireOpenStage(Connection connection, UUID revisionId) throws SQLException {
        if (count(connection, """
                SELECT COUNT(*) FROM onmaru.audio_revision_stages
                WHERE revision_id = ? AND NOT ready AND failure_code IS NULL
                """, revisionId) != 1) {
            throw new IllegalStateException("stage is missing or terminal");
        }
    }

    private boolean stageReady(Connection connection, UUID revisionId) throws SQLException {
        return count(connection, """
                SELECT COUNT(*) FROM onmaru.audio_revision_stages
                WHERE revision_id = ? AND ready
                """, revisionId) == 1;
    }

    private boolean leaseMatches(
            Connection connection,
            SyncRunLease lease,
            Instant publishedAt
    ) throws SQLException {
        try (var statement = connection.prepareStatement("""
                SELECT owner_token, generation, lease_until
                FROM onmaru.operations_sync_leases
                WHERE dataset = ?
                FOR UPDATE
                """)) {
            statement.setString(1, lease.dataset());
            try (var result = statement.executeQuery()) {
                return result.next()
                        && lease.ownerToken().equals(result.getString("owner_token"))
                        && lease.generation() == result.getInt("generation")
                        && instant(result, "lease_until").isAfter(publishedAt);
            }
        }
    }

    private UUID findActiveRevision(Connection connection, String dataset, boolean lock) throws SQLException {
        String sql = "SELECT revision_id FROM onmaru.catalog_active_datasets WHERE dataset = ?"
                + (lock ? " FOR UPDATE" : "");
        try (var statement = connection.prepareStatement(sql)) {
            statement.setString(1, dataset);
            try (var result = statement.executeQuery()) {
                return result.next() ? result.getObject(1, UUID.class) : null;
            }
        }
    }

    private boolean revisionExists(Connection connection, UUID revisionId) throws SQLException {
        return revisionId != null && count(connection, """
                SELECT COUNT(*) FROM onmaru.catalog_dataset_revisions WHERE id = ?
                """, revisionId) == 1;
    }

    private UUID identityId(
            Connection connection,
            String table,
            String firstColumn,
            String firstValue,
            String secondColumn,
            String secondValue,
            String thirdColumn,
            String thirdValue
    ) throws SQLException {
        String sql = "SELECT id FROM onmaru." + table
                + " WHERE " + firstColumn + " = ? AND " + secondColumn + " = ? AND " + thirdColumn + " = ?";
        try (var statement = connection.prepareStatement(sql)) {
            statement.setString(1, firstValue);
            statement.setString(2, secondValue);
            statement.setString(3, thirdValue);
            try (var result = statement.executeQuery()) {
                if (!result.next()) {
                    throw new IllegalStateException("audio identity upsert failed");
                }
                return result.getObject(1, UUID.class);
            }
        }
    }

    private UUID stableId(String type, Object identity) {
        return UUID.nameUUIDFromBytes((type + ":" + identity).getBytes(StandardCharsets.UTF_8));
    }

    private long count(Connection connection, String sql, UUID... values) throws SQLException {
        try (var statement = connection.prepareStatement(sql)) {
            for (int index = 0; index < values.length; index++) {
                statement.setObject(index + 1, values[index]);
            }
            try (var result = statement.executeQuery()) {
                result.next();
                return result.getLong(1);
            }
        }
    }

    private void execute(Connection connection, String sql, StatementBinder binder) throws SQLException {
        try (var statement = connection.prepareStatement(sql)) {
            binder.bind(statement);
            statement.executeUpdate();
        }
    }

    private void setInstant(PreparedStatement statement, int index, Instant value) throws SQLException {
        if (value == null) {
            statement.setNull(index, java.sql.Types.TIMESTAMP_WITH_TIMEZONE);
        } else {
            statement.setObject(index, value.atOffset(ZoneOffset.UTC));
        }
    }

    private Instant instant(ResultSet result, String column) throws SQLException {
        var value = result.getObject(column, java.time.OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private <T> T query(SqlOperation<T> operation) {
        try (var connection = dataSource.getConnection()) {
            return operation.execute(connection);
        } catch (SQLException exception) {
            throw new IllegalStateException("audio database operation failed", exception);
        }
    }

    private <T> T transaction(SqlOperation<T> operation) {
        return transaction(Connection.TRANSACTION_READ_COMMITTED, operation);
    }

    private <T> T transaction(int isolation, SqlOperation<T> operation) {
        try (var connection = dataSource.getConnection()) {
            connection.setTransactionIsolation(isolation);
            connection.setAutoCommit(false);
            try {
                T result = operation.execute(connection);
                connection.commit();
                return result;
            } catch (RuntimeException | SQLException exception) {
                connection.rollback();
                throw exception;
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("audio database transaction failed", exception);
        }
    }

    @FunctionalInterface
    private interface SqlOperation<T> {
        T execute(Connection connection) throws SQLException;
    }

    @FunctionalInterface
    private interface StatementBinder {
        void bind(PreparedStatement statement) throws SQLException;
    }
}
