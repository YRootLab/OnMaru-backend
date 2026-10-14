package com.yrootlab.onmaru.scheduling.catalog.detail;

import com.fasterxml.jackson.databind.JsonNode;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

public final class JdbcDetailCollectionStore implements DetailCollectionStore {

    private static final String LOCK_SQL = "SELECT pg_try_advisory_lock(hashtext('onmaru.tourapi.detail-collection'))";
    private static final String UNLOCK_SQL = "SELECT pg_advisory_unlock(hashtext('onmaru.tourapi.detail-collection'))";

    private final DataSource dataSource;

    public JdbcDetailCollectionStore(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public int attemptsToday() {
        try (Connection connection = dataSource.getConnection();
             var statement = connection.prepareStatement("""
                     SELECT count(*)
                     FROM onmaru.tourapi_detail_snapshots
                     WHERE last_attempted_at >= date_trunc('day', now() AT TIME ZONE 'Asia/Seoul') AT TIME ZONE 'Asia/Seoul'
                     """);
             var result = statement.executeQuery()) {
            result.next();
            return result.getInt(1);
        } catch (SQLException exception) {
            throw databaseFailure(exception);
        }
    }

    @Override
    public List<DetailCollectionTarget> nextTargets(int limit) {
        try (Connection connection = dataSource.getConnection();
             var statement = connection.prepareStatement("""
                     SELECT contentid, contenttypeid, category
                     FROM (
                         SELECT DISTINCT ON (c.contentid)
                                c.contentid, c.contenttypeid, v.category,
                                bucket.rank AS bucket_rank,
                                COALESCE(s.status, 'NEW') AS prior
                         FROM onmaru.catalog_place_versions v
                         JOIN onmaru.catalog_active_datasets a
                           ON a.revision_id = v.revision_id AND a.dataset = 'kto-korean-tour'
                         JOIN onmaru.catalog_kto_korean_content_versions c
                           ON c.revision_id = v.revision_id AND c.source_ref_id = v.source_ref_id
                         CROSS JOIN LATERAL (
                             SELECT CASE
                                 WHEN v.category = 'HANOK' THEN 1
                                 WHEN v.category = 'HANOK_STAY' THEN 2
                                 WHEN v.category = 'HANOK_CAFE' THEN 3
                                 WHEN v.category = 'HANOK_EXPERIENCE' THEN 4
                                 WHEN v.category = 'HISTORIC_SITE' AND c.lcls_systm3 = ? THEN 5
                                 WHEN v.category = 'TRADITIONAL_MARKET' THEN 6
                             END AS rank
                         ) bucket
                         LEFT JOIN onmaru.tourapi_detail_snapshots s ON s.content_id = c.contentid
                         WHERE v.status = 'ACTIVE'
                           AND bucket.rank IS NOT NULL
                           AND (s.content_id IS NULL OR s.status <> 'OK')
                         ORDER BY c.contentid, bucket.rank
                     ) candidates
                     ORDER BY (prior = 'FAILED'), bucket_rank, contentid
                     LIMIT ?
                     """)) {
            statement.setString(1, DetailCollectionPolicy.PALACE_LCLS_SYSTM3);
            statement.setInt(2, limit);
            List<DetailCollectionTarget> targets = new ArrayList<>();
            try (var result = statement.executeQuery()) {
                while (result.next()) {
                    targets.add(new DetailCollectionTarget(
                            result.getString("contentid"),
                            result.getString("contenttypeid"),
                            result.getString("category")));
                }
            }
            return targets;
        } catch (SQLException exception) {
            throw databaseFailure(exception);
        }
    }

    @Override
    public void save(DetailCollectionTarget target, DetailFetchResult result, Instant attemptedAt) {
        DetailPayloads payloads = result.payloads();
        JsonNode common = payloads == null ? null : payloads.common();
        String commonJson = common == null ? null : common.toString();
        String introJson = payloads == null || payloads.intro() == null ? null : payloads.intro().toString();
        String infoJson = payloads == null || payloads.info() == null ? null : payloads.info().toString();
        String digestSource = commonJson == null ? null : commonJson + "\n" + introJson + "\n" + infoJson;
        boolean ok = result.status() == DetailFetchResult.Status.OK;
        try (Connection connection = dataSource.getConnection();
             var statement = connection.prepareStatement("""
                     INSERT INTO onmaru.tourapi_detail_snapshots
                         (content_id, content_type_id, category, status, overview_length, payload,
                          intro_payload, info_payload, payload_sha256, error_code, attempts,
                          last_attempted_at, fetched_at)
                     VALUES (?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?::jsonb, ?, ?, 1, ?, ?)
                     ON CONFLICT (content_id) DO UPDATE SET
                         content_type_id = EXCLUDED.content_type_id,
                         category = EXCLUDED.category,
                         status = EXCLUDED.status,
                         overview_length = EXCLUDED.overview_length,
                         payload = EXCLUDED.payload,
                         intro_payload = EXCLUDED.intro_payload,
                         info_payload = EXCLUDED.info_payload,
                         payload_sha256 = EXCLUDED.payload_sha256,
                         error_code = EXCLUDED.error_code,
                         attempts = tourapi_detail_snapshots.attempts + 1,
                         last_attempted_at = EXCLUDED.last_attempted_at,
                         fetched_at = EXCLUDED.fetched_at
                     """)) {
            statement.setString(1, target.contentId());
            statement.setString(2, target.contentTypeId());
            statement.setString(3, target.category());
            statement.setString(4, result.status().name());
            statement.setInt(5, ok ? overviewLength(common) : 0);
            statement.setString(6, commonJson);
            statement.setString(7, introJson);
            statement.setString(8, infoJson);
            statement.setString(9, digestSource == null ? null : sha256(digestSource));
            statement.setString(10, result.errorCode());
            statement.setTimestamp(11, Timestamp.from(attemptedAt));
            statement.setTimestamp(12, ok ? Timestamp.from(attemptedAt) : null);
            statement.executeUpdate();
        } catch (SQLException exception) {
            throw databaseFailure(exception);
        }
    }

    @Override
    public AutoCloseable tryLock() {
        Connection connection = null;
        try {
            connection = dataSource.getConnection();
            try (var statement = connection.prepareStatement(LOCK_SQL);
                 var result = statement.executeQuery()) {
                result.next();
                if (!result.getBoolean(1)) {
                    connection.close();
                    return null;
                }
            }
            Connection held = connection;
            return () -> {
                try (var statement = held.prepareStatement(UNLOCK_SQL)) {
                    statement.executeQuery();
                } finally {
                    held.close();
                }
            };
        } catch (SQLException exception) {
            closeQuietly(connection);
            throw databaseFailure(exception);
        }
    }

    private static int overviewLength(JsonNode payload) {
        if (payload == null) {
            return 0;
        }
        String overview = payload.path("overview").asText("");
        return overview.codePointCount(0, overview.length());
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }

    private static void closeQuietly(Connection connection) {
        if (connection == null) {
            return;
        }
        try {
            connection.close();
        } catch (SQLException ignored) {
            // 원인 예외를 가리지 않도록 닫기 실패는 무시한다.
        }
    }

    /** 예외 메시지에는 SQL 값이 섞일 수 있으므로 원문을 노출하지 않는다. */
    private static IllegalStateException databaseFailure(SQLException exception) {
        return new IllegalStateException("tourapi detail collection persistence failed: sqlState="
                + exception.getSQLState(), exception);
    }
}
