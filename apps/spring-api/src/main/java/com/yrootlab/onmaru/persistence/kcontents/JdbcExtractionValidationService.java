package com.yrootlab.onmaru.persistence.kcontents;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yrootlab.onmaru.kcontents.canonicalization.WorkIdentity;
import com.yrootlab.onmaru.kcontents.validation.ExtractionValidator;
import com.yrootlab.onmaru.kcontents.validation.ExtractionValidator.Candidate;
import com.yrootlab.onmaru.kcontents.validation.ExtractionValidator.Evidence;
import com.yrootlab.onmaru.kcontents.validation.ExtractionValidator.Place;
import org.springframework.stereotype.Component;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Applies a submitted job only after deterministic server-side evidence validation. */
@Component
@ConditionalOnProperty(name="onmaru.kcontents.research.enabled",havingValue="true")
public final class JdbcExtractionValidationService {
    private final DataSource dataSource;
    private final ObjectMapper json;
    private final ExtractionValidator validator = new ExtractionValidator();

    public JdbcExtractionValidationService(DataSource dataSource, ObjectMapper json) {
        this.dataSource = dataSource;
        this.json = json;
    }

    public int processAvailable(int limit) {
        if (limit < 1 || limit > 100) throw new IllegalArgumentException("Invalid validation limit");
        List<UUID> jobs = tx(connection -> {
            var ids = new ArrayList<UUID>();
            try (var statement = connection.prepareStatement("""
                    SELECT j.id FROM onmaru.k_content_research_jobs j
                    WHERE j.status='SUCCEEDED' AND NOT EXISTS
                      (SELECT 1 FROM onmaru.k_content_validation_runs v
                       WHERE v.job_id=j.id AND v.requeue_epoch=j.requeue_epoch)
                    ORDER BY j.completed_at,j.id LIMIT ?
                    """)) {
                statement.setInt(1, limit);
                try (var rows = statement.executeQuery()) { while (rows.next()) ids.add(rows.getObject(1, UUID.class)); }
            }
            return ids;
        });
        int processed = 0;
        for (UUID id : jobs) if (process(id)) processed++;
        return processed;
    }

    public boolean process(UUID jobId) {
        return tx(connection -> {
            Job job = readJobForUpdate(connection, jobId);
            if (job == null || !"SUCCEEDED".equals(job.status())) return false;
            if (existsValidation(connection, jobId, job.epoch())) return false;
            List<Evidence> sources = readEvidence(connection, jobId);
            Place place = readPlace(connection, job);
            ExtractionValidator.Report report;
            try {
                report = validator.validate(job.placeId(), job.sourceFingerprint(), job.schemaVersion(),
                        job.promptVersion(), job.modelVersion(), job.resultStatus(), job.resultJson(), place, sources);
            } catch (IllegalArgumentException invalid) {
                String fingerprint = ExtractionValidator.fingerprint(job.placeId(), job.sourceFingerprint(),
                        job.schemaVersion(), job.promptVersion(), job.modelVersion(), sources);
                report = new ExtractionValidator.Report("REJECTED", List.of(), fingerprint);
            }
            UUID runId = UUID.randomUUID();
            execute(connection, """
                    INSERT INTO onmaru.k_content_validation_runs
                    (id,job_id,requeue_epoch,input_fingerprint,source_fingerprint,schema_version,prompt_version,model_version,rule_version,status,candidate_count)
                    VALUES (?,?,?,?,?,?,?,?,?,?,?)
                    """, runId, jobId, job.epoch(), report.fingerprint(), job.sourceFingerprint(), job.schemaVersion(), job.promptVersion(),
                    job.modelVersion(), ExtractionValidator.RULE_VERSION, report.status(), report.candidates().size());
            Map<UUID, Evidence> byId = new HashMap<>();
            sources.forEach(source -> byId.put(source.id(), source));
            int index = 0;
            for (Candidate candidate : report.candidates()) {
                applyCandidate(connection, runId, index++, job, candidate, byId);
            }
            if (report.candidates().isEmpty() && !"NO_MATCH".equals(report.status()))
                review(connection, runId, 0, null,
                        "STALE".equals(report.status()) ? "EXTRACTION_VERSION_STALE" : "NO_VALID_CANDIDATE",
                        job.resultJson());
            audit(connection, runId, null, "VALIDATED", "server:" + ExtractionValidator.RULE_VERSION, report.status());
            return true;
        });
    }

    public List<ReviewItem> openReviews(int limit) {
        if (limit < 1 || limit > 100) throw new IllegalArgumentException("Invalid review limit");
        return tx(connection -> {
            List<ReviewItem> result = new ArrayList<>();
            try (var statement = connection.prepareStatement("""
                    SELECT id,validation_run_id,relation_id,reason_code,candidate_json::text
                    FROM onmaru.k_content_validation_reviews WHERE state='OPEN'
                    ORDER BY created_at,id LIMIT ?
                    """)) {
                statement.setInt(1, limit);
                try (var rows = statement.executeQuery()) {
                    while (rows.next()) result.add(new ReviewItem(rows.getObject(1, UUID.class),
                            rows.getObject(2, UUID.class), rows.getObject(3, UUID.class), rows.getString(4), rows.getString(5)));
                }
            }
            return List.copyOf(result);
        });
    }

    /** A reviewer may approve a relation only when a verified evidence row already exists. */
    public void decide(UUID reviewId, String actor, boolean approve, List<UUID> confirmedEvidenceIds) {
        if (actor == null || actor.isBlank()) throw new IllegalArgumentException("Reviewer required");
        tx(connection -> {
            UUID runId, relationId;
            String reason, candidateJson;
            try (var statement = connection.prepareStatement("""
                    SELECT validation_run_id,relation_id,reason_code,candidate_json::text FROM onmaru.k_content_validation_reviews
                    WHERE id=? AND state='OPEN' FOR UPDATE
                    """)) {
                statement.setObject(1, reviewId);
                try (var rows = statement.executeQuery()) {
                    if (!rows.next()) throw new IllegalStateException("REVIEW_NOT_OPEN");
                    runId = rows.getObject(1, UUID.class); relationId = rows.getObject(2, UUID.class);
                    reason = rows.getString(3); candidateJson = rows.getString(4);
                }
            }
            if (approve) {
                if (relationId == null || !List.of("INDEPENDENT_SOURCE_REQUIRED","WORKER_UNCERTAIN").contains(reason)
                        || confirmedEvidenceIds == null || confirmedEvidenceIds.isEmpty())
                    throw new IllegalStateException("RELATION_REVIEW_REQUIRED");
                if (!exists(connection,
                        "SELECT 1 FROM onmaru.k_content_place_relations WHERE id=? AND status='REVIEW_REQUIRED'", relationId))
                    throw new IllegalStateException("RELATION_REVIEW_STALE");
                requireCurrentReview(connection, runId, relationId);
                var claimed = new java.util.HashSet<UUID>();
                try {
                    for (JsonNode claim : json.readTree(candidateJson).path("claims"))
                        claimed.add(relationEvidenceForResearch(connection, runId, relationId,
                                UUID.fromString(claim.path("evidenceId").asText())));
                } catch (Exception invalid) { throw new IllegalStateException("REVIEW_EVIDENCE_INVALID", invalid); }
                if (confirmedEvidenceIds.stream().distinct().count() != confirmedEvidenceIds.size()
                        || !claimed.containsAll(confirmedEvidenceIds))
                    throw new IllegalStateException("REVIEW_EVIDENCE_INVALID");
                for (UUID evidenceId : confirmedEvidenceIds) {
                    try (var check = connection.prepareStatement("""
                            SELECT 1 FROM onmaru.k_content_relation_evidence
                            WHERE id=? AND relation_id=? AND status IN ('VERIFIED','REVIEW_REQUIRED') FOR UPDATE
                            """)) {
                        check.setObject(1, evidenceId); check.setObject(2, relationId);
                        try (var rows = check.executeQuery()) { if (!rows.next()) throw new IllegalStateException("EVIDENCE_NOT_IN_REVIEW"); }
                    }
                    execute(connection, """
                            UPDATE onmaru.k_content_relation_evidence SET status='VERIFIED',verified_by=?,
                              verified_at=now() WHERE id=? AND relation_id=? AND status='REVIEW_REQUIRED'
                            """, actor, evidenceId, relationId);
                }
                execute(connection, """
                        UPDATE onmaru.k_content_place_relations SET status='HUMAN_VERIFIED',verified_by=?,
                          verified_at=now(),review_reason=NULL,updated_at=now() WHERE id=?
                        """, actor, relationId);
                execute(connection, """
                        UPDATE onmaru.k_contents SET status='HUMAN_VERIFIED',verified_by=?,verified_at=now(),
                          updated_at=now() WHERE id=(SELECT k_content_id FROM onmaru.k_content_place_relations WHERE id=?)
                          AND status='REVIEW_REQUIRED'
                        """, actor, relationId);
            }
            execute(connection, """
                    UPDATE onmaru.k_content_validation_reviews SET state=?,reviewed_by=?,reviewed_at=now()
                    WHERE id=?
                    """, approve ? "APPROVED" : "REJECTED", actor, reviewId);
            audit(connection, runId, reviewId, approve ? "APPROVED" : "REJECTED", actor, null);
            return null;
        });
    }

    public record ReviewItem(UUID id, UUID runId, UUID relationId, String reason, String candidateJson) { }
    private record Job(UUID id, UUID placeId, String sourceFingerprint, String inputJson, String status, int epoch,
                       String schemaVersion, String promptVersion, String modelVersion,
                       String resultStatus, String resultJson) { }

    private Job readJobForUpdate(Connection connection, UUID id) throws SQLException {
        try (var statement = connection.prepareStatement("""
                SELECT id,place_id,source_fingerprint,input_json::text,status,requeue_epoch,
                  coalesce(schema_version,'legacy'),coalesce(prompt_version,'legacy'),
                  coalesce(model_version,'legacy'),result_status,result_json::text
                FROM onmaru.k_content_research_jobs WHERE id=? FOR UPDATE
                """)) {
            statement.setObject(1, id);
            try (var rows = statement.executeQuery()) {
                if (!rows.next()) return null;
                return new Job(rows.getObject(1, UUID.class), rows.getObject(2, UUID.class), rows.getString(3),
                        rows.getString(4), rows.getString(5), rows.getInt(6), rows.getString(7), rows.getString(8),
                        rows.getString(9), rows.getString(10), rows.getString(11));
            }
        }
    }

    private Place readPlace(Connection connection, Job job) throws SQLException {
        try {
            JsonNode input = json.readTree(job.inputJson());
            String suppliedName = first(input, "placeName", "placeTitle", "title");
            String suppliedRegion = first(input, "region", "regionName");
            try (var statement = connection.prepareStatement("""
                    SELECT v.name,r.name,v.address FROM onmaru.catalog_place_versions v
                    JOIN onmaru.catalog_active_datasets a ON a.revision_id=v.revision_id
                    LEFT JOIN onmaru.catalog_regions r ON r.id=v.region_id
                    WHERE v.place_id=? AND v.status='ACTIVE'
                    ORDER BY a.activated_at DESC LIMIT 1
                    """)) {
                statement.setObject(1, job.placeId());
                try (var rows = statement.executeQuery()) {
                    if (rows.next()) return placeFromCatalog(job.placeId(), suppliedName, suppliedRegion,
                            rows.getString(1), rows.getString(2), rows.getString(3));
                }
            }
            try (var statement = connection.prepareStatement("""
                    SELECT raw->>'title',coalesce(m.name,split_part(raw->>'addr1',' ',1)),raw->>'addr1'
                    FROM onmaru.selected_discovery_public_visible p
                    LEFT JOIN onmaru.map_region_display_names m ON m.provider_code=p.region_code
                    WHERE p.place_id=? LIMIT 1
                    """)) {
                statement.setObject(1, job.placeId());
                try (var rows = statement.executeQuery()) {
                    if (rows.next()) return placeFromCatalog(job.placeId(), suppliedName, suppliedRegion,
                            rows.getString(1), rows.getString(2), rows.getString(3));
                }
            }
            return new Place(job.placeId(), null, null);
        } catch (com.fasterxml.jackson.core.JsonProcessingException invalid) {
            return new Place(job.placeId(), null, null);
        }
    }
    private static Place placeFromCatalog(UUID id, String suppliedName, String suppliedRegion,
                                          String officialName, String officialRegion, String officialAddress) {
        if (!WorkIdentity.normalize(officialName).equals(WorkIdentity.normalize(suppliedName))
                || !regionMatches(officialRegion, officialAddress, suppliedRegion)) return new Place(id, null, null);
        return new Place(id, suppliedName, suppliedRegion);
    }
    private static boolean regionMatches(String official, String address, String supplied) {
        String left = WorkIdentity.normalize(official), right = WorkIdentity.normalize(supplied);
        if (left.isEmpty() || right.isEmpty()) return false;
        String addressKey = WorkIdentity.normalize(address);
        if (!addressKey.isEmpty()) return (addressKey.startsWith(right)
                || regionAliasKey(addressKey).startsWith(regionAliasKey(right))) && right.length() >= 2;
        String stem = left.replaceFirst("(특별자치도|특별자치시|특별시|광역시|자치도|도)$", "");
        return left.equals(right) || left.startsWith(right) && right.length() >= 2
                || right.startsWith(stem) && stem.length() >= 2;
    }
    private static String regionAliasKey(String value) {
        return value.replace("특별자치도", "").replace("특별자치시", "")
                .replace("특별시", "").replace("광역시", "")
                .replaceFirst("^([^시군구]+)도", "$1");
    }
    private static String first(JsonNode root, String... names) {
        for (String name : names) {
            String value = root.path(name).asText("");
            if (!value.isBlank()) return value;
        }
        return null;
    }

    private List<Evidence> readEvidence(Connection connection, UUID jobId) throws SQLException {
        List<Evidence> result = new ArrayList<>();
        try (var statement = connection.prepareStatement("""
                SELECT id,canonical_url,title,publisher,excerpt,source_verified_at FROM onmaru.k_content_research_evidence
                WHERE job_id=? ORDER BY canonical_url,id
                """)) {
            statement.setObject(1, jobId);
            try (var rows = statement.executeQuery()) {
                while (rows.next()) {
                    String url = rows.getString(2), publisher = rows.getString(4);
                    boolean official = official(url, publisher);
                    result.add(new Evidence(rows.getObject(1, UUID.class), url, rows.getString(3), publisher,
                            rows.getString(5), official, rows.getTimestamp(6) != null));
                }
            }
        }
        return result;
    }

    private void applyCandidate(Connection connection, UUID runId, int index, Job job, Candidate candidate,
                                Map<UUID, Evidence> sources) throws Exception {
        if ("REJECTED".equals(candidate.status())) {
            review(connection, runId, index, null, candidate.reason(), json.writeValueAsString(candidate));
            return;
        }
        try (var lock = connection.prepareStatement("SELECT pg_advisory_xact_lock(hashtextextended(?,0))")) {
            lock.setString(1, "kcontents-work|" + candidate.workType() + "|" + candidate.normalizedTitle());
            lock.execute();
        }
        List<UUID> matches = new ArrayList<>();
        try (var statement = connection.prepareStatement("""
                SELECT DISTINCT w.id,w.release_year,w.season_key FROM onmaru.k_contents w
                LEFT JOIN onmaru.k_content_aliases a ON a.k_content_id=w.id
                WHERE w.work_type=? AND (w.normalized_title=? OR a.normalized_alias=?)
                """)) {
            statement.setString(1, candidate.workType()); statement.setString(2, candidate.normalizedTitle());
            statement.setString(3, candidate.normalizedTitle());
            try (var rows = statement.executeQuery()) {
                while (rows.next()) {
                    Integer year = (Integer) rows.getObject(2);
                    if (!WorkIdentity.conflicts(year, rows.getString(3), candidate.releaseYear(), candidate.seasonKey()))
                        matches.add(rows.getObject(1, UUID.class));
                }
            }
        }
        if (matches.size() > 1) {
            review(connection, runId, index, null, "WORK_HOMONYM", json.writeValueAsString(candidate));
            return;
        }
        UUID work = matches.isEmpty() ? UUID.randomUUID() : matches.getFirst();
        if (matches.isEmpty()) execute(connection, """
                INSERT INTO onmaru.k_contents(id,title,normalized_title,work_type,release_year,season_key,
                  status,verified_by,verified_at,rule_version,model_version)
                VALUES (?,?,?,?,?,?,?,CASE WHEN ?='AUTO_VERIFIED' THEN 'server' END,
                  CASE WHEN ?='AUTO_VERIFIED' THEN now() END,?,?)
                """, work, candidate.title(), candidate.normalizedTitle(), candidate.workType(),
                candidate.releaseYear(), candidate.seasonKey(), candidate.status(), candidate.status(), candidate.status(),
                ExtractionValidator.RULE_VERSION, job.modelVersion());
        else if ("AUTO_VERIFIED".equals(candidate.status())) execute(connection, """
                UPDATE onmaru.k_contents SET status='AUTO_VERIFIED',verified_by='server',verified_at=now(),
                  rule_version=?,model_version=?,updated_at=now()
                WHERE id=? AND status NOT IN ('HUMAN_VERIFIED','REJECTED')
                """, ExtractionValidator.RULE_VERSION, job.modelVersion(), work);
        UUID relation = UUID.randomUUID();
        try (var statement = connection.prepareStatement("""
                INSERT INTO onmaru.k_content_place_relations
                (id,place_id,k_content_id,relation_type,status,confidence,verified_by,verified_at,rule_version,model_version,review_reason)
                VALUES (?,?,?,'FILMING_LOCATION',?,?,CASE WHEN ?='AUTO_VERIFIED' THEN 'server' END,
                  CASE WHEN ?='AUTO_VERIFIED' THEN now() END,?,?,?)
                ON CONFLICT (place_id,k_content_id,relation_type) DO UPDATE SET
                  status=CASE WHEN onmaru.k_content_place_relations.status='HUMAN_VERIFIED'
                    OR (onmaru.k_content_place_relations.status='AUTO_VERIFIED' AND EXCLUDED.status='REVIEW_REQUIRED')
                    THEN onmaru.k_content_place_relations.status ELSE EXCLUDED.status END,
                  confidence=CASE WHEN onmaru.k_content_place_relations.status='HUMAN_VERIFIED'
                    OR (onmaru.k_content_place_relations.status='AUTO_VERIFIED' AND EXCLUDED.status='REVIEW_REQUIRED')
                    THEN onmaru.k_content_place_relations.confidence ELSE EXCLUDED.confidence END,
                  verified_by=CASE WHEN onmaru.k_content_place_relations.status='HUMAN_VERIFIED'
                    OR (onmaru.k_content_place_relations.status='AUTO_VERIFIED' AND EXCLUDED.status='REVIEW_REQUIRED')
                    THEN onmaru.k_content_place_relations.verified_by ELSE EXCLUDED.verified_by END,
                  verified_at=CASE WHEN onmaru.k_content_place_relations.status='HUMAN_VERIFIED'
                    OR (onmaru.k_content_place_relations.status='AUTO_VERIFIED' AND EXCLUDED.status='REVIEW_REQUIRED')
                    THEN onmaru.k_content_place_relations.verified_at ELSE EXCLUDED.verified_at END,
                  updated_at=now(),review_reason=CASE WHEN onmaru.k_content_place_relations.status='HUMAN_VERIFIED'
                    OR (onmaru.k_content_place_relations.status='AUTO_VERIFIED' AND EXCLUDED.status='REVIEW_REQUIRED')
                    THEN onmaru.k_content_place_relations.review_reason ELSE EXCLUDED.review_reason END
                RETURNING id
                """)) {
            bind(statement, relation, job.placeId(), work, candidate.status(), candidate.confidence(),
                    candidate.status(), candidate.status(), ExtractionValidator.RULE_VERSION,
                    job.modelVersion(), candidate.reason());
            try (var rows = statement.executeQuery()) { rows.next(); relation = rows.getObject(1, UUID.class); }
        }
        for (var claim : candidate.claims()) {
            Evidence source = sources.get(claim.evidenceId());
            if (source == null) continue;
            String evidenceStatus = "AUTO_VERIFIED".equals(candidate.status()) ? "VERIFIED" : "REVIEW_REQUIRED";
            execute(connection, """
                    INSERT INTO onmaru.k_content_relation_evidence
                    (id,relation_id,canonical_url,source_type,title,publisher,filming_excerpt,observed_at,
                      status,verified_by,verified_at,rule_version,model_version)
                    VALUES (?,?,?,?,?,?,?,now(),?,CASE WHEN ?='VERIFIED' THEN 'server' END,
                      CASE WHEN ?='VERIFIED' THEN now() END,?,?)
                    ON CONFLICT (relation_id,canonical_url) DO NOTHING
                    """, UUID.randomUUID(), relation, source.url(), source.official() ? "OFFICIAL" : "WEB",
                    source.title(), source.publisher(), source.excerpt(), evidenceStatus, evidenceStatus,
                    evidenceStatus, ExtractionValidator.RULE_VERSION, job.modelVersion());
            execute(connection, """
                    INSERT INTO onmaru.k_content_metadata_sources
                    (id,k_content_id,canonical_url,source_type,title,publisher,excerpt,observed_at,status,
                      verified_by,verified_at,rule_version,model_version)
                    VALUES (?,?,?,?,?,?,?,now(),?,CASE WHEN ?='VERIFIED' THEN 'server' END,
                      CASE WHEN ?='VERIFIED' THEN now() END,?,?)
                    ON CONFLICT (k_content_id,canonical_url) DO NOTHING
                    """, UUID.randomUUID(), work, source.url(), source.official() ? "OFFICIAL" : "WEB",
                    source.title(), source.publisher(), source.excerpt(), evidenceStatus, evidenceStatus,
                    evidenceStatus, ExtractionValidator.RULE_VERSION, job.modelVersion());
        }
        for (String alias : candidate.aliases()) {
            boolean cited = candidate.claims().stream().anyMatch(claim ->
                    WorkIdentity.normalize(claim.quote()).contains(WorkIdentity.normalize(alias)));
            if (cited) execute(connection, """
                    INSERT INTO onmaru.k_content_aliases(id,k_content_id,alias_text,normalized_alias)
                    VALUES (?,?,?,?) ON CONFLICT (k_content_id,normalized_alias) DO NOTHING
                    """, UUID.randomUUID(), work, alias, WorkIdentity.normalize(alias));
        }
        applyTagsAndSummaries(connection, runId, index, work, relation, candidate, sources);
        if ("REVIEW_REQUIRED".equals(candidate.status()))
            review(connection, runId, index, relation, candidate.reason(), json.writeValueAsString(candidate));
    }

    private void applyTagsAndSummaries(Connection connection, UUID runId, int index, UUID work, UUID relation,
                                       Candidate candidate, Map<UUID, Evidence> sources) throws Exception {
        for (var tag : candidate.tags()) {
            if (!tag.valid()) {
                review(connection, runId, index, relation, tag.reason(), json.writeValueAsString(tag));
                continue;
            }
            UUID evidenceId = relationEvidenceId(connection, relation, sources.get(tag.evidenceId()));
            if (evidenceId == null) continue;
            String normalized = WorkIdentity.normalize(tag.rawLabel());
            UUID sourceId = metadataSourceId(connection, work, sources.get(tag.evidenceId()));
            UUID tagId = tagId(connection, tag.scope(), tag.groupHint(), normalized);
            boolean verified = evidenceVerified(connection, evidenceId);
            if (tagId != null && verified) {
                if ("RELATION_TAG".equals(tag.scope())) execute(connection, """
                        INSERT INTO onmaru.k_content_relation_tags(relation_id,tag_id,evidence_id,status)
                        VALUES (?,?,?,'VERIFIED') ON CONFLICT (relation_id,tag_id) DO NOTHING
                        """, relation, tagId, evidenceId);
                else if (sourceId != null) execute(connection, """
                        INSERT INTO onmaru.k_content_work_tags(k_content_id,tag_id,source_id,status)
                        VALUES (?,?,?,'VERIFIED') ON CONFLICT (k_content_id,tag_id) DO NOTHING
                        """, work, tagId, sourceId);
            } else {
                if ("RELATION_TAG".equals(tag.scope())) execute(connection, """
                        INSERT INTO onmaru.k_content_tag_candidates
                        (id,scope,relation_id,evidence_id,raw_label,normalized_label,group_hint,supporting_quote,status)
                        VALUES (?,'RELATION_TAG',?,?,?,?,?,?,'REVIEW_REQUIRED') ON CONFLICT DO NOTHING
                        """, UUID.randomUUID(), relation, evidenceId, tag.rawLabel(), normalized, tag.groupHint(), tag.quote());
                else if (sourceId != null) execute(connection, """
                        INSERT INTO onmaru.k_content_tag_candidates
                        (id,scope,k_content_id,source_id,raw_label,normalized_label,group_hint,supporting_quote,status)
                        VALUES (?,'WORK_TAG',?,?,?,?,?,?,'REVIEW_REQUIRED') ON CONFLICT DO NOTHING
                        """, UUID.randomUUID(), work, sourceId, tag.rawLabel(), normalized, tag.groupHint(), tag.quote());
                review(connection, runId, index, relation, "TAG_REGISTRY_REVIEW", json.writeValueAsString(tag));
            }
        }
        for (var summary : candidate.summaries()) {
            if (!summary.valid()) {
                review(connection, runId, index, relation, summary.reason(), json.writeValueAsString(summary));
                continue;
            }
            UUID evidenceId = relationEvidenceId(connection, relation, sources.get(summary.evidenceId()));
            if (evidenceId == null) continue;
            String status = evidenceVerified(connection, evidenceId) ? "VERIFIED" : "REVIEW_REQUIRED";
            if ("RELATION".equals(summary.scope())) execute(connection, """
                    INSERT INTO onmaru.k_content_relation_summary_points
                    (id,relation_id,position,summary_text,evidence_id,status,generated_at,prompt_version)
                    VALUES (?,?,1,?,?,?,now(),?) ON CONFLICT (relation_id,position) DO NOTHING
                    """, UUID.randomUUID(), relation, summary.text(), evidenceId, status, ExtractionValidator.PROMPT_VERSION);
            else {
                UUID sourceId = metadataSourceId(connection, work, sources.get(summary.evidenceId()));
                if (sourceId != null) execute(connection, """
                        INSERT INTO onmaru.k_content_work_summary_points
                        (id,k_content_id,position,summary_text,source_id,status,generated_at,prompt_version)
                        VALUES (?,?,1,?,?,?,now(),?) ON CONFLICT (k_content_id,position) DO NOTHING
                        """, UUID.randomUUID(), work, summary.text(), sourceId, status, ExtractionValidator.PROMPT_VERSION);
            }
        }
    }

    private UUID metadataSourceId(Connection connection, UUID work, Evidence source) throws SQLException {
        if (source == null) return null;
        try (var statement = connection.prepareStatement("""
                SELECT id FROM onmaru.k_content_metadata_sources WHERE k_content_id=? AND canonical_url=?
                """)) {
            statement.setObject(1, work); statement.setString(2, source.url());
            try (var rows = statement.executeQuery()) { return rows.next() ? rows.getObject(1, UUID.class) : null; }
        }
    }
    private UUID tagId(Connection connection, String scope, String group, String normalized) throws SQLException {
        try (var statement = connection.prepareStatement("""
                SELECT t.id FROM onmaru.k_content_tag_aliases a
                JOIN onmaru.k_content_tags t ON t.id=a.tag_id
                WHERE a.scope=? AND a.group_code=? AND a.normalized_alias=? AND t.active
                """)) {
            statement.setString(1, scope); statement.setString(2, group); statement.setString(3, normalized);
            try (var rows = statement.executeQuery()) { return rows.next() ? rows.getObject(1, UUID.class) : null; }
        }
    }
    private boolean evidenceVerified(Connection connection, UUID evidenceId) throws SQLException {
        return exists(connection, "SELECT 1 FROM onmaru.k_content_relation_evidence WHERE id=? AND status='VERIFIED'", evidenceId);
    }

    private UUID relationEvidenceId(Connection connection, UUID relation, Evidence source) throws SQLException {
        if (source == null) return null;
        try (var statement = connection.prepareStatement("""
                SELECT id FROM onmaru.k_content_relation_evidence WHERE relation_id=? AND canonical_url=?
                """)) {
            statement.setObject(1, relation); statement.setString(2, source.url());
            try (var rows = statement.executeQuery()) { return rows.next() ? rows.getObject(1, UUID.class) : null; }
        }
    }
    private UUID relationEvidenceForResearch(Connection connection, UUID validationRun, UUID relation,
                                             UUID researchEvidence) throws SQLException {
        try (var statement = connection.prepareStatement("""
                SELECT relation_evidence.id FROM onmaru.k_content_validation_runs validation
                JOIN onmaru.k_content_research_evidence research ON research.job_id=validation.job_id
                JOIN onmaru.k_content_relation_evidence relation_evidence
                  ON relation_evidence.relation_id=? AND relation_evidence.canonical_url=research.canonical_url
                WHERE validation.id=? AND research.id=?
                """)) {
            statement.setObject(1, relation); statement.setObject(2, validationRun);
            statement.setObject(3, researchEvidence);
            try (var rows = statement.executeQuery()) { return rows.next() ? rows.getObject(1, UUID.class) : null; }
        }
    }
    private void requireCurrentReview(Connection connection, UUID runId, UUID relationId) throws SQLException {
        UUID jobId; int epoch; String sourceFingerprint, inputFingerprint;
        try (var statement = connection.prepareStatement("""
                SELECT job_id,requeue_epoch,source_fingerprint,input_fingerprint
                FROM onmaru.k_content_validation_runs WHERE id=?
                """)) {
            statement.setObject(1, runId);
            try (var rows = statement.executeQuery()) {
                if (!rows.next()) throw new IllegalStateException("REVIEW_STALE");
                jobId=rows.getObject(1,UUID.class);epoch=rows.getInt(2);
                sourceFingerprint=rows.getString(3);inputFingerprint=rows.getString(4).trim();
            }
        }
        Job current=readJobForUpdate(connection,jobId);
        if (current==null || !"SUCCEEDED".equals(current.status()) || current.epoch()!=epoch
                || !sourceFingerprint.equals(current.sourceFingerprint())
                || !inputFingerprint.equals(ExtractionValidator.fingerprint(current.placeId(),current.sourceFingerprint(),
                    current.schemaVersion(),current.promptVersion(),current.modelVersion(),readEvidence(connection,jobId))))
            throw new IllegalStateException("REVIEW_STALE");
        try (var statement = connection.prepareStatement("""
                SELECT 1 FROM onmaru.k_content_validation_reviews later
                JOIN onmaru.k_content_validation_runs newer ON newer.id=later.validation_run_id
                JOIN onmaru.k_content_validation_runs original ON original.id=?
                WHERE later.relation_id=? AND newer.validated_at>original.validated_at LIMIT 1
                """)) {
            statement.setObject(1,runId);statement.setObject(2,relationId);
            try (var rows = statement.executeQuery()) { if (rows.next()) throw new IllegalStateException("REVIEW_STALE"); }
        }
    }

    private void review(Connection connection, UUID runId, int index, UUID relationId, String reason, String payload) throws SQLException {
        execute(connection, """
                INSERT INTO onmaru.k_content_validation_reviews
                (id,validation_run_id,candidate_index,relation_id,reason_code,candidate_json)
                VALUES (?,?,?,?,?,?::jsonb)
            """, UUID.randomUUID(), runId, index, relationId, reason == null ? "REVIEW_REQUIRED" : reason, payload);
    }

    private void audit(Connection connection, UUID runId, UUID reviewId, String action, String actor, String detail) throws SQLException {
        execute(connection, """
                INSERT INTO onmaru.k_content_validation_audit(id,validation_run_id,review_id,action,actor,detail_code)
                VALUES (?,?,?,?,?,?)
                """, UUID.randomUUID(), runId, reviewId, action, actor, detail);
    }

    private static boolean official(String url, String publisher) {
        try {
            String host = new java.net.URI(url).getHost().toLowerCase(java.util.Locale.ROOT);
            return host.endsWith(".go.kr") || host.equals("kto.or.kr") || host.endsWith(".kto.or.kr");
        } catch (Exception ignored) { return false; }
    }

    private static boolean exists(Connection connection, String sql, Object id) throws SQLException {
        try (var statement = connection.prepareStatement(sql)) {
            statement.setObject(1, id);
            try (var rows = statement.executeQuery()) { return rows.next(); }
        }
    }
    private static boolean existsValidation(Connection connection, UUID jobId, int epoch) throws SQLException {
        try (var statement = connection.prepareStatement("""
                SELECT 1 FROM onmaru.k_content_validation_runs WHERE job_id=? AND requeue_epoch=?
                """)) {
            statement.setObject(1, jobId); statement.setInt(2, epoch);
            try (var rows = statement.executeQuery()) { return rows.next(); }
        }
    }
    private static void execute(Connection connection, String sql, Object... params) throws SQLException {
        try (var statement = connection.prepareStatement(sql)) { bind(statement, params); statement.executeUpdate(); }
    }
    private static void bind(PreparedStatement statement, Object... params) throws SQLException {
        for (int i=0;i<params.length;i++) statement.setObject(i+1, params[i]);
    }
    private <T> T tx(Work<T> work) {
        try (var connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try { T result = work.run(connection); connection.commit(); return result; }
            catch (Exception failure) {
                connection.rollback();
                if (failure instanceof RuntimeException runtime) throw runtime;
                throw new IllegalStateException("K-Contents validation failed", failure);
            }
        } catch (SQLException failure) { throw new IllegalStateException("K-Contents validation storage unavailable", failure); }
    }
    private interface Work<T> { T run(Connection connection) throws Exception; }
}
