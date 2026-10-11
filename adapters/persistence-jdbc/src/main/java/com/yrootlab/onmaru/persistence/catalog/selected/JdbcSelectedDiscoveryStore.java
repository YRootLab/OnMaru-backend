package com.yrootlab.onmaru.persistence.catalog.selected;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yrootlab.onmaru.catalog.application.qualification.DiscoveryCandidatePolicy;
import com.yrootlab.onmaru.catalog.application.qualification.SourceRecord;
import com.yrootlab.onmaru.catalog.application.selectedsync.SelectedDiscoveryApprovalService;
import com.yrootlab.onmaru.catalog.application.selectedsync.SelectedDiscoverySync;
import com.yrootlab.onmaru.catalog.application.sourcefetch.SelectedSourceFetch;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** PostgreSQL staging and CAS publication for the discovery dataset only. */
public final class JdbcSelectedDiscoveryStore implements SelectedDiscoverySync.Store, SelectedDiscoveryApprovalService.CandidateStore {
    private final DataSource dataSource;
    private final ObjectMapper json;

    public JdbcSelectedDiscoveryStore(DataSource dataSource, ObjectMapper json) {
        this.dataSource = Objects.requireNonNull(dataSource);
        this.json = Objects.requireNonNull(json);
    }

    @Override public boolean claim(UUID runId, Instant dueAt) {
        return tx(connection -> {
            try (var insert = connection.prepareStatement("""
                    INSERT INTO onmaru.selected_discovery_runs(id,due_at,status) VALUES (?,?,'RUNNING')
                    ON CONFLICT DO NOTHING
                    """)) {
                insert.setObject(1, runId); insert.setTimestamp(2, java.sql.Timestamp.from(dueAt));
                if (insert.executeUpdate() == 1) return true;
            }
            try (var select = connection.prepareStatement("SELECT status FROM onmaru.selected_discovery_runs WHERE id=? AND due_at=? FOR UPDATE")) {
                select.setObject(1, runId); select.setTimestamp(2, java.sql.Timestamp.from(dueAt));
                try (var rs = select.executeQuery()) {
                    if (!rs.next() || !"FAILED".equals(rs.getString(1))) return false;
                }
            }
            try (var delete = connection.prepareStatement("DELETE FROM onmaru.selected_discovery_checkpoints WHERE run_id=?")) {
                delete.setObject(1, runId); delete.executeUpdate();
            }
            // A failed publication can leave a staged revision. Remove only this run's unpublished rows.
            for (String table : List.of("selected_discovery_quarantines", "selected_discovery_candidates", "selected_discovery_public_items")) {
                try (var delete = connection.prepareStatement("DELETE FROM onmaru." + table + " WHERE revision_id=?")) {
                    delete.setObject(1, runId); delete.executeUpdate();
                }
            }
            try (var delete = connection.prepareStatement("DELETE FROM onmaru.selected_discovery_revisions WHERE id=? AND status='STAGED'")) {
                delete.setObject(1, runId); delete.executeUpdate();
            }
            try (var update = connection.prepareStatement("UPDATE onmaru.selected_discovery_runs SET status='RUNNING',failure_code=NULL,detail_requests=0,started_at=now(),finished_at=NULL WHERE id=?")) {
                update.setObject(1, runId); update.executeUpdate();
            }
            return true;
        });
    }

    @Override public void checkpoint(UUID runId, SelectedSourceFetch.Checkpoint checkpoint) {
        execute("""
                INSERT INTO onmaru.selected_discovery_checkpoints
                (run_id,operation,filter_value,page_number,received,expected,candidate_count,quarantine_count)
                VALUES (?,?,?,?,?,?,?,?) ON CONFLICT (run_id,operation,filter_value,page_number)
                DO UPDATE SET received=excluded.received,expected=excluded.expected,
                  candidate_count=excluded.candidate_count,quarantine_count=excluded.quarantine_count
                """, runId, checkpoint.query().operation(), checkpoint.query().filter(), checkpoint.pageNumber(),
                checkpoint.received(), checkpoint.expected(), checkpoint.candidateCount(), checkpoint.quarantineCount());
    }

    @Override public void detailProgress(UUID runId, int requests) {
        execute("UPDATE onmaru.selected_discovery_runs SET detail_requests=? WHERE id=?", requests, runId);
    }

    @Override public Map<String, SelectedDiscoverySync.Candidate> previousCandidates() {
        var result = new LinkedHashMap<String, SelectedDiscoverySync.Candidate>();
        query("""
                SELECT c.* FROM onmaru.selected_discovery_candidates c
                JOIN onmaru.selected_discovery_revisions r ON r.id=c.revision_id
                JOIN onmaru.selected_discovery_runs run ON run.id=r.id
                WHERE c.revision_id=(SELECT id FROM onmaru.selected_discovery_revisions
                    WHERE status IN ('STAGED','PUBLISHED') AND id IN
                      (SELECT id FROM onmaru.selected_discovery_runs WHERE status IN ('STAGED','PUBLISHED'))
                    ORDER BY created_at DESC,id DESC LIMIT 1)
                """, rs -> {
            String id = rs.getString("content_id");
            var decision = new DiscoveryCandidatePolicy.Decision(
                    DiscoveryCandidatePolicy.Status.valueOf(rs.getString("decision")),
                    rs.getString("role") == null ? null : DiscoveryCandidatePolicy.Role.valueOf(rs.getString("role")),
                    rs.getString("reason_code"), rs.getString("policy_version"), false);
            result.put(id, new SelectedDiscoverySync.Candidate(id, source(rs.getString("raw")),
                    rs.getString("list_hash").trim(), rs.getString("detail_hash").trim(),
                    rs.getString("hash_schema_version"), decision,
                    SelectedDiscoverySync.Diff.valueOf(rs.getString("diff_status")), rs.getString("modifiedtime")));
        });
        return Map.copyOf(result);
    }

    @Override public Map<String, SelectedDiscoverySync.PublicItem> activePublic() {
        var result = new LinkedHashMap<String, SelectedDiscoverySync.PublicItem>();
        query("""
                SELECT p.* FROM onmaru.selected_discovery_public_items p
                JOIN onmaru.selected_discovery_active a ON a.revision_id=p.revision_id
                """, rs -> result.put(rs.getString("content_id"), new SelectedDiscoverySync.PublicItem(
                rs.getString("content_id"), source(rs.getString("raw")),
                DiscoveryCandidatePolicy.Role.valueOf(rs.getString("role")), null)));
        return Map.copyOf(result);
    }

    @Override public Map<String, SelectedDiscoverySync.Approval> approvals() {
        var result = new LinkedHashMap<String, SelectedDiscoverySync.Approval>();
        query("SELECT * FROM onmaru.selected_discovery_approvals", rs -> result.put(rs.getString("content_id"),
                new SelectedDiscoverySync.Approval(rs.getString("list_hash").trim(), rs.getString("detail_hash").trim(),
                        DiscoveryCandidatePolicy.Role.valueOf(rs.getString("role")), rs.getString("source_fingerprint"),
                        rs.getBoolean("detail_reviewed"), rs.getBoolean("rights_reviewed"),
                        rs.getString("evidence_ref"), rs.getString("approved_by"))));
        return Map.copyOf(result);
    }

    @Override public UUID activeRevisionId() {
        final UUID[] result = {null};
        query("SELECT revision_id FROM onmaru.selected_discovery_active", rs -> result[0] = rs.getObject(1, UUID.class));
        return result[0];
    }

    @Override public void stage(UUID runId, Map<String, SelectedDiscoverySync.Candidate> candidates,
                                List<SelectedDiscoverySync.Review> reviews, SelectedDiscoverySync.Report report) {
        tx(connection -> {
            UUID base = activeRevisionId(connection);
            try (var insert = connection.prepareStatement("""
                    INSERT INTO onmaru.selected_discovery_revisions
                    (id,base_revision_id,status,policy_version,hash_schema_version,added_count,changed_count,
                     unchanged_count,missing_count,quarantine_count,approved_count,detail_requests,counts_by_region,counts_by_role)
                    VALUES (?,?,'STAGED',?,?,?,?,?,?,?,?,?,?::jsonb,?::jsonb)
                    """)) {
                insert.setObject(1, runId); insert.setObject(2, base);
                insert.setString(3, DiscoveryCandidatePolicy.VERSION); insert.setString(4, "discovery-source-hash-v1");
                insert.setInt(5, report.added()); insert.setInt(6, report.changed()); insert.setInt(7, report.unchanged());
                insert.setInt(8, report.missing()); insert.setInt(9, report.quarantined());
                insert.setInt(10, report.approved()); insert.setInt(11, report.detailRequests());
                insert.setString(12, json(report.byRegion())); insert.setString(13, json(report.byRole()));
                insert.executeUpdate();
            }
            try (var insert = connection.prepareStatement("""
                    INSERT INTO onmaru.selected_discovery_candidates
                    (revision_id,content_id,raw,list_hash,detail_hash,hash_schema_version,policy_version,
                     decision,role,reason_code,diff_status,modifiedtime)
                    VALUES (?,?,?::jsonb,?,?,?,?,?,?,?,?,?)
                    """)) {
                for (var candidate : candidates.values()) {
                    insert.setObject(1, runId); insert.setString(2, candidate.contentId());
                    insert.setString(3, json(candidate.row().fields())); insert.setString(4, candidate.listHash());
                    insert.setString(5, candidate.detailHash()); insert.setString(6, candidate.hashSchemaVersion());
                    insert.setString(7, candidate.policyVersion()); insert.setString(8, candidate.decision().status().name());
                    insert.setString(9, candidate.decision().role() == null ? null : candidate.decision().role().name());
                    insert.setString(10, candidate.decision().reasonCode()); insert.setString(11, candidate.diff().name());
                    insert.setString(12, candidate.modifiedtime()); insert.addBatch();
                }
                insert.executeBatch();
            }
            try (var insert = connection.prepareStatement("""
                    INSERT INTO onmaru.selected_discovery_quarantines
                    (id,revision_id,content_id,reason_code,decision,policy_reason,raw)
                    VALUES (?,?,?,?,?,?,?::jsonb)
                    """)) {
                for (var review : reviews) {
                    insert.setObject(1, UUID.randomUUID()); insert.setObject(2, runId);
                    insert.setString(3, review.row().field("contentid")); insert.setString(4, review.reason());
                    insert.setString(5, review.decision().status().name()); insert.setString(6, review.decision().reasonCode());
                    insert.setString(7, json(review.row().fields())); insert.addBatch();
                }
                insert.executeBatch();
            }
            update(connection, "UPDATE onmaru.selected_discovery_runs SET status='STAGED',finished_at=now() WHERE id=?", runId);
            return null;
        });
    }

    @Override public boolean publish(UUID runId, UUID expectedActive,
                                     Map<String, SelectedDiscoverySync.PublicItem> items, SelectedDiscoverySync.Report report) {
        return tx(connection -> {
            lockPublication(connection);
            UUID actual = activeRevisionIdForUpdate(connection);
            if (!Objects.equals(actual, expectedActive)) return false;
            for (var item : items.values()) if (!approvalStillCurrent(connection, item)) return false;
            try (var insert = connection.prepareStatement("""
                    INSERT INTO onmaru.selected_discovery_public_items
                    (revision_id,content_id,place_id,role,region_code,raw) VALUES (?,?,?,?,?,?::jsonb)
                    """)) {
                for (var item : items.values()) {
                    UUID placeId = resolvePlaceId(connection, item.contentId());
                    insert.setObject(1, runId); insert.setString(2, item.contentId()); insert.setObject(3, placeId);
                    insert.setString(4, item.role().name()); insert.setString(5, item.row().field("areacode"));
                    insert.setString(6, json(item.row().fields())); insert.addBatch();
                }
                insert.executeBatch();
            }
            update(connection, "UPDATE onmaru.selected_discovery_revisions SET status='PUBLISHED',published_at=now() WHERE id=? AND status='STAGED'", runId);
            update(connection, """
                    INSERT INTO onmaru.selected_discovery_active(singleton,revision_id,activated_at)
                    VALUES (true,?,now()) ON CONFLICT (singleton) DO UPDATE SET revision_id=excluded.revision_id,activated_at=excluded.activated_at
                    """, runId);
            update(connection, "UPDATE onmaru.selected_discovery_runs SET status='PUBLISHED',finished_at=now() WHERE id=?", runId);
            return true;
        });
    }

    @Override public void fail(UUID runId, String code) {
        execute("UPDATE onmaru.selected_discovery_runs SET status='FAILED',failure_code=?,finished_at=now() WHERE id=? AND status<>'PUBLISHED'", code, runId);
    }

    @Override public SourceRecord latestCandidate(String contentId) {
        final SourceRecord[] result = {null};
        tx(connection -> {
            try (var statement = connection.prepareStatement("""
                    SELECT c.raw FROM onmaru.selected_discovery_candidates c
                    WHERE c.content_id=? AND c.diff_status<>'MISSING'
                      AND c.revision_id=(SELECT r.id FROM onmaru.selected_discovery_revisions r
                        JOIN onmaru.selected_discovery_runs run ON run.id=r.id
                        WHERE run.status IN ('STAGED','PUBLISHED')
                        ORDER BY r.created_at DESC,r.id DESC LIMIT 1)
                    """)) {
                statement.setString(1, contentId);
                try (var rs = statement.executeQuery()) { if (rs.next()) result[0] = source(rs.getString(1)); }
            }
            return null;
        });
        return result[0];
    }

    @Override public void saveApproval(String contentId, SelectedDiscoverySync.Approval approval) {
        tx(connection -> {
            lockPublication(connection);
            update(connection, """
                    INSERT INTO onmaru.selected_discovery_approvals
                    (content_id,list_hash,detail_hash,role,source_fingerprint,detail_reviewed,rights_reviewed,
                     evidence_ref,approved_by,approved_at)
                    VALUES (?,?,?,?,?,?,?,?,?,now())
                    ON CONFLICT (content_id) DO UPDATE SET list_hash=excluded.list_hash,detail_hash=excluded.detail_hash,
                      role=excluded.role,source_fingerprint=excluded.source_fingerprint,
                      detail_reviewed=excluded.detail_reviewed,rights_reviewed=excluded.rights_reviewed,
                      evidence_ref=excluded.evidence_ref,approved_by=excluded.approved_by,approved_at=excluded.approved_at
                    """, contentId, approval.listHash(), approval.detailHash(), approval.role().name(),
                    approval.sourceFingerprint(), approval.detailReviewed(), approval.rightsReviewed(),
                    approval.evidenceRef(), approval.approvedBy());
            update(connection, """
                    INSERT INTO onmaru.selected_discovery_approval_audit
                    (id,content_id,action,actor,list_hash,detail_hash,evidence_ref)
                    VALUES (?,?,'APPROVE',?,?,?,?)
                    """, UUID.randomUUID(), contentId, approval.approvedBy(), approval.listHash(),
                    approval.detailHash(), approval.evidenceRef());
            return null;
        });
    }

    @Override public void revokeApproval(String contentId, String reviewedBy) {
        tx(connection -> {
            lockPublication(connection);
            update(connection, "DELETE FROM onmaru.selected_discovery_approvals WHERE content_id=?", contentId);
            update(connection, """
                    INSERT INTO onmaru.selected_discovery_approval_audit(id,content_id,action,actor)
                    VALUES (?,?,'REVOKE',?)
                    """, UUID.randomUUID(), contentId, reviewedBy);
            return null;
        });
    }

    /** Repoint only the discovery view to a previously published revision. */
    public boolean rollback(UUID expectedActive, UUID targetRevision) {
        return tx(connection -> {
            lockPublication(connection);
            if (!Objects.equals(activeRevisionIdForUpdate(connection), expectedActive)) return false;
            try (var check = connection.prepareStatement("SELECT 1 FROM onmaru.selected_discovery_revisions WHERE id=? AND status='PUBLISHED'")) {
                check.setObject(1, targetRevision);
                try (var rs = check.executeQuery()) { if (!rs.next()) return false; }
            }
            update(connection, "UPDATE onmaru.selected_discovery_active SET revision_id=?,activated_at=now() WHERE singleton=true", targetRevision);
            return true;
        });
    }

    private UUID resolvePlaceId(Connection connection, String contentId) throws SQLException {
        try (var select = connection.prepareStatement("""
                SELECT place_id FROM onmaru.catalog_place_sources
                WHERE provider='kto-tourapi-korean' AND external_id=? AND language='ko-KR'
                ORDER BY CASE dataset WHEN 'kto-korean-tour' THEN 0 ELSE 1 END LIMIT 1
                """)) {
            select.setString(1, contentId);
            try (var rs = select.executeQuery()) { if (rs.next()) return rs.getObject(1, UUID.class); }
        }
        UUID placeId = UUID.randomUUID();
        update(connection, "INSERT INTO onmaru.catalog_place_identity(id,created_at) VALUES (?,now())", placeId);
        try (var insert = connection.prepareStatement("""
                INSERT INTO onmaru.catalog_place_sources
                (id,place_id,provider,dataset,external_id,language,fetched_at)
                VALUES (?,?,'kto-tourapi-korean','kto-korean-tour',?,'ko-KR',now())
                ON CONFLICT ON CONSTRAINT catalog_place_sources_provider_dataset_external_id_language_uq DO NOTHING
                """)) {
            insert.setObject(1, UUID.randomUUID()); insert.setObject(2, placeId); insert.setString(3, contentId);
            if (insert.executeUpdate() == 1) return placeId;
        }
        update(connection, "DELETE FROM onmaru.catalog_place_identity WHERE id=?", placeId);
        try (var select = connection.prepareStatement("""
                SELECT place_id FROM onmaru.catalog_place_sources
                WHERE provider='kto-tourapi-korean' AND dataset='kto-korean-tour'
                  AND external_id=? AND language='ko-KR'
                """)) {
            select.setString(1, contentId);
            try (var rs = select.executeQuery()) {
                if (rs.next()) return rs.getObject(1, UUID.class);
            }
        }
        throw new IllegalStateException("TourAPI source identity race could not be resolved");
    }

    private void lockPublication(Connection connection) throws SQLException {
        try (var lock = connection.prepareStatement("SELECT pg_advisory_xact_lock(hashtext('onmaru.selected_discovery_active'))")) {
            lock.execute();
        }
    }

    private boolean approvalStillCurrent(Connection connection, SelectedDiscoverySync.PublicItem item) throws SQLException {
        var expected = item.approval();
        if (expected == null) return false;
        try (var select = connection.prepareStatement("""
                SELECT list_hash,detail_hash,role,source_fingerprint,detail_reviewed,rights_reviewed,evidence_ref,approved_by
                FROM onmaru.selected_discovery_approvals WHERE content_id=? FOR SHARE
                """)) {
            select.setString(1, item.contentId());
            try (var rs = select.executeQuery()) {
                return rs.next()
                        && Objects.equals(rs.getString("list_hash").trim(), expected.listHash())
                        && Objects.equals(rs.getString("detail_hash").trim(), expected.detailHash())
                        && Objects.equals(rs.getString("role"), expected.role().name())
                        && Objects.equals(rs.getString("source_fingerprint"), expected.sourceFingerprint())
                        && rs.getBoolean("detail_reviewed") && rs.getBoolean("rights_reviewed")
                        && Objects.equals(rs.getString("evidence_ref"), expected.evidenceRef())
                        && Objects.equals(rs.getString("approved_by"), expected.approvedBy());
            }
        }
    }

    private UUID activeRevisionId(Connection connection) throws SQLException {
        try (var statement = connection.prepareStatement("SELECT revision_id FROM onmaru.selected_discovery_active");
             var rs = statement.executeQuery()) { return rs.next() ? rs.getObject(1, UUID.class) : null; }
    }

    private UUID activeRevisionIdForUpdate(Connection connection) throws SQLException {
        try (var statement = connection.prepareStatement("SELECT revision_id FROM onmaru.selected_discovery_active FOR UPDATE");
             var rs = statement.executeQuery()) { return rs.next() ? rs.getObject(1, UUID.class) : null; }
    }

    private SourceRecord source(String raw) {
        try { return new SourceRecord("kto-tourapi-korean", "areaBasedList2", json.readValue(raw, new TypeReference<>() { })); }
        catch (Exception exception) { throw new IllegalStateException("Invalid stored discovery source", exception); }
    }

    private String json(Object value) {
        try { return json.writeValueAsString(value); }
        catch (Exception exception) { throw new IllegalStateException("Cannot encode discovery source", exception); }
    }

    private void execute(String sql, Object... params) { tx(connection -> { update(connection, sql, params); return null; }); }

    private void update(Connection connection, String sql, Object... params) throws SQLException {
        try (var statement = connection.prepareStatement(sql)) {
            for (int index = 0; index < params.length; index++) statement.setObject(index + 1, params[index]);
            statement.executeUpdate();
        }
    }

    private void query(String sql, Row row) {
        tx(connection -> {
            try (var statement = connection.prepareStatement(sql); var rs = statement.executeQuery()) {
                while (rs.next()) row.accept(rs);
            }
            return null;
        });
    }

    private <T> T tx(Action<T> action) {
        try (var connection = dataSource.getConnection()) {
            boolean original = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                T result = action.run(connection);
                connection.commit();
                return result;
            } catch (Exception exception) {
                connection.rollback();
                if (exception instanceof RuntimeException runtime) throw runtime;
                throw new IllegalStateException("Discovery persistence failed", exception);
            } finally { connection.setAutoCommit(original); }
        } catch (SQLException exception) { throw new IllegalStateException("Discovery database unavailable", exception); }
    }

    @FunctionalInterface private interface Action<T> { T run(Connection connection) throws Exception; }
    @FunctionalInterface private interface Row { void accept(ResultSet row) throws SQLException; }
}
