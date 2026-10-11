package com.yrootlab.onmaru.persistence.kcontents;

import com.yrootlab.onmaru.kcontents.research.ResearchSubmission;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.net.URI;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

@Component
@ConditionalOnProperty(name="onmaru.kcontents.research.enabled",havingValue="true")
public final class JdbcResearchJobStore {
    private final DataSource dataSource;
    private final int dailyLeaseBudget;
    private static final ObjectMapper JSON = new ObjectMapper();
    public JdbcResearchJobStore(DataSource dataSource) { this(dataSource,1000); }
    @Autowired public JdbcResearchJobStore(DataSource dataSource,
            @Value("${onmaru.kcontents.research.daily-lease-budget:1000}") int dailyLeaseBudget) {
        if (dailyLeaseBudget<1) throw new IllegalArgumentException("dailyLeaseBudget must be positive");
        this.dataSource=dataSource;this.dailyLeaseBudget=dailyLeaseBudget;
    }

    public record Lease(UUID jobId, UUID placeId, String reason, String sourceFingerprint,
                        String inputJson, String leaseToken, Instant expiresAt, int attempt) { }
    public record Evidence(String url, String title, String publisher, String excerpt) { }
    public record JobStatus(UUID id, String status, int attempts, int maxAttempts, Instant availableAt, String failureCode) { }

    public UUID enqueue(UUID placeId, String reason, String fingerprint, String inputJson) {
        if (placeId == null || blank(reason) || blank(fingerprint) || inputJson == null || inputJson.length() > 100_000)
            throw new IllegalArgumentException("Invalid job input");
        return tx(connection -> {
            UUID id = UUID.randomUUID();
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO onmaru.k_content_research_jobs(id,place_id,reason,source_fingerprint,input_json)
                    VALUES (?,?,?,?,?::jsonb) ON CONFLICT (place_id,reason,source_fingerprint) DO UPDATE SET updated_at=now()
                    RETURNING id
                    """)) {
                statement.setObject(1, id); statement.setObject(2, placeId); statement.setString(3, reason);
                statement.setString(4, fingerprint); statement.setString(5, inputJson);
                try (ResultSet result = statement.executeQuery()) { result.next(); return (UUID) result.getObject(1); }
            }
        });
    }

    public Lease lease(String worker) {
        if (blank(worker) || worker.length() > 100) throw new IllegalArgumentException("Invalid worker");
        return tx(connection -> {
            try(PreparedStatement lock=connection.prepareStatement("SELECT pg_advisory_xact_lock(686)")) {lock.execute();}
            try(PreparedStatement budget=connection.prepareStatement("""
                    SELECT count(*) FROM onmaru.k_content_research_runs WHERE started_at>=date_trunc('day',now())
                    """)) {
                try(ResultSet result=budget.executeQuery()){result.next();if(result.getInt(1)>=dailyLeaseBudget)return null;}
            }
            try (PreparedStatement runs = connection.prepareStatement("""
                    UPDATE onmaru.k_content_research_runs r SET finished_at=now(),outcome='EXPIRED',failure_code='LEASE_EXPIRED'
                    FROM onmaru.k_content_research_jobs j
                    WHERE r.job_id=j.id AND r.requeue_epoch=j.requeue_epoch AND r.attempt=j.attempts AND r.finished_at IS NULL
                      AND j.status='LEASED' AND j.lease_expires_at<=now() AND j.attempts>=j.max_attempts
                    """)) {runs.executeUpdate();}
            try (PreparedStatement expire = connection.prepareStatement("""
                    UPDATE onmaru.k_content_research_jobs SET status='FAILED',lease_owner=NULL,lease_token_hash=NULL,
                    lease_expires_at=NULL,last_failure_code='LEASE_EXPIRED',updated_at=now()
                    WHERE status='LEASED' AND lease_expires_at<=now() AND attempts>=max_attempts
                    """)) { expire.executeUpdate(); }
            UUID id, placeId; String reason, fingerprint, input; int attempts,epoch; boolean expired;
            try (PreparedStatement select = connection.prepareStatement("""
                    SELECT id,place_id,reason,source_fingerprint,input_json::text,attempts,status,requeue_epoch
                    FROM onmaru.k_content_research_jobs
                    WHERE attempts<max_attempts AND ((status='QUEUED' AND available_at<=now())
                       OR (status='LEASED' AND lease_expires_at<=now()))
                    ORDER BY available_at,created_at,id FOR UPDATE SKIP LOCKED LIMIT 1
                    """)) {
                try (ResultSet result = select.executeQuery()) {
                    if (!result.next()) return null;
                    id=(UUID)result.getObject(1); placeId=(UUID)result.getObject(2); reason=result.getString(3);
                    fingerprint=result.getString(4); input=result.getString(5); attempts=result.getInt(6)+1;
                    expired="LEASED".equals(result.getString(7));epoch=result.getInt(8);
                }
            }
            if (expired) {
                try(PreparedStatement old=connection.prepareStatement("""
                        UPDATE onmaru.k_content_research_runs SET finished_at=now(),outcome='EXPIRED',failure_code='LEASE_EXPIRED'
                        WHERE job_id=? AND requeue_epoch=? AND attempt=? AND finished_at IS NULL
                        """)) {old.setObject(1,id);old.setInt(2,epoch);old.setInt(3,attempts-1);old.executeUpdate();}
            }
            String token=UUID.randomUUID().toString()+UUID.randomUUID();
            Instant expires=Instant.now().plusSeconds(900);
            try (PreparedStatement update=connection.prepareStatement("""
                    UPDATE onmaru.k_content_research_jobs SET status='LEASED',attempts=?,lease_owner=?,
                    lease_token_hash=?,lease_expires_at=?,updated_at=now() WHERE id=?
                    """)) {
                update.setInt(1,attempts);update.setString(2,worker);update.setString(3,sha256(token));
                update.setTimestamp(4,Timestamp.from(expires));update.setObject(5,id);update.executeUpdate();
            }
            try(PreparedStatement run=connection.prepareStatement("INSERT INTO onmaru.k_content_research_runs(id,job_id,requeue_epoch,attempt,worker_id) VALUES (?,?,?,?,?)")) {
                run.setObject(1,UUID.randomUUID());run.setObject(2,id);run.setInt(3,epoch);run.setInt(4,attempts);run.setString(5,worker);run.executeUpdate();
            }
            event(connection,id,"LEASED",worker,null);
            return new Lease(id,placeId,reason,fingerprint,input,token,expires,attempts);
        });
    }

    public List<UUID> addEvidence(UUID jobId, String worker, String token, List<Evidence> bundle) {
        if (bundle == null || bundle.isEmpty() || bundle.size()>20) throw new IllegalArgumentException("Invalid evidence bundle");
        return tx(connection -> {
            requireLease(connection,jobId,worker,token);
            List<UUID> ids=new ArrayList<>();
            for (Evidence evidence:bundle) {
                if (evidence == null || blank(evidence.url())
                        || blank(evidence.title()) || blank(evidence.excerpt()) || evidence.excerpt().length()>1000)
                    throw new IllegalArgumentException("Invalid evidence");
                String url=canonicalUrl(evidence.url());
                try (PreparedStatement insert=connection.prepareStatement("""
                        INSERT INTO onmaru.k_content_research_evidence(id,job_id,canonical_url,title,publisher,excerpt)
                        VALUES (?,?,?,?,?,?) ON CONFLICT (job_id,canonical_url) DO UPDATE SET title=EXCLUDED.title
                        RETURNING id
                        """)) {
                    insert.setObject(1,UUID.randomUUID());insert.setObject(2,jobId);insert.setString(3,url);
                    insert.setString(4,evidence.title());insert.setString(5,evidence.publisher());insert.setString(6,evidence.excerpt());
                    try (ResultSet result=insert.executeQuery()) {result.next();ids.add((UUID)result.getObject(1));}
                }
            }
            event(connection,jobId,"EVIDENCE_ADDED",worker,Integer.toString(ids.size()));
            return List.copyOf(ids);
        });
    }

    public String submit(UUID jobId, String worker, String token, String idempotencyKey, ResearchSubmission submission) {
        if (blank(idempotencyKey) || idempotencyKey.length()>100) throw new IllegalArgumentException("Invalid idempotency key");
        if (submission.evidenceIds().stream().distinct().count()!=submission.evidenceIds().size())
            throw new IllegalArgumentException("DUPLICATE_EVIDENCE_ID");
        if (!new HashSet<>(submission.evidenceIds()).equals(referencedEvidence(submission.resultJson())))
            throw new IllegalArgumentException("EVIDENCE_REFERENCE_MISMATCH");
        String requestHash=sha256(submission.toString());
        return tx(connection -> {
            lockJob(connection,jobId);
            try (PreparedStatement receipt=connection.prepareStatement("""
                    SELECT request_hash,outcome,worker_id,lease_token_hash FROM onmaru.k_content_research_receipts
                    WHERE job_id=? AND idempotency_key=?
                    """)) {
                receipt.setObject(1,jobId);receipt.setString(2,idempotencyKey);
                try (ResultSet result=receipt.executeQuery()) {
                    if (result.next()) {
                        if (!worker.equals(result.getString(3)) || blank(token) || !sha256(token).equals(result.getString(4)))
                            throw new IllegalStateException("LEASE_INVALID");
                        if (!requestHash.equals(result.getString(1))) throw new IllegalStateException("IDEMPOTENCY_CONFLICT");
                        return result.getString(2);
                    }
                }
            }
            requireLease(connection,jobId,worker,token);
            if (!submission.evidenceIds().isEmpty()) {
                try (PreparedStatement owned=connection.prepareStatement("""
                        SELECT count(DISTINCT id) FROM onmaru.k_content_research_evidence WHERE job_id=? AND id=ANY(?)
                        """)) {
                    owned.setObject(1,jobId);
                    owned.setArray(2,connection.createArrayOf("uuid",submission.evidenceIds().toArray()));
                    try (ResultSet result=owned.executeQuery()) {
                        result.next(); if (result.getInt(1)!=submission.evidenceIds().size())
                            throw new IllegalArgumentException("EVIDENCE_NOT_OWNED");
                    }
                }
            }
            try (PreparedStatement update=connection.prepareStatement("""
                    UPDATE onmaru.k_content_research_jobs SET status='SUCCEEDED',result_status=?,result_json=?::jsonb,
                    schema_version=?,prompt_version=?,model_version=?,
                    completed_at=now(),lease_owner=NULL,lease_token_hash=NULL,lease_expires_at=NULL,
                    last_idempotency_key=?,last_request_hash=?,updated_at=now() WHERE id=?
                    """)) {
                update.setString(1,submission.resultStatus());update.setString(2,submission.resultJson());
                update.setString(3,submission.schemaVersion());update.setString(4,submission.promptVersion());
                update.setString(5,submission.modelVersion());
                update.setString(6,idempotencyKey);update.setString(7,requestHash);update.setObject(8,jobId);update.executeUpdate();
            }
            receipt(connection,jobId,idempotencyKey,requestHash,"SUCCEEDED",worker,token);
            finishRun(connection,jobId,"SUCCEEDED",null);
            event(connection,jobId,"SUBMITTED",worker,submission.resultStatus());
            return "SUCCEEDED";
        });
    }

    public String fail(UUID jobId,String worker,String token,String idempotencyKey,String code) {
        if (blank(idempotencyKey) || !List.of("TIMEOUT","RATE_LIMITED","CLI_FAILURE","INVALID_JSON","OTHER").contains(code))
            throw new IllegalArgumentException("Invalid failure");
        String hash=sha256(code);
        return tx(connection -> {
            lockJob(connection,jobId);
            try (PreparedStatement receipt=connection.prepareStatement("SELECT request_hash,outcome,worker_id,lease_token_hash FROM onmaru.k_content_research_receipts WHERE job_id=? AND idempotency_key=?")) {
                receipt.setObject(1,jobId);receipt.setString(2,idempotencyKey);
                try(ResultSet result=receipt.executeQuery()) {if(result.next()) {
                    if (!worker.equals(result.getString(3)) || blank(token) || !sha256(token).equals(result.getString(4)))
                        throw new IllegalStateException("LEASE_INVALID");
                    if(!hash.equals(result.getString(1))) throw new IllegalStateException("IDEMPOTENCY_CONFLICT");
                    return result.getString(2);
                }}
            }
            int attempt=requireLease(connection,jobId,worker,token);
            boolean quarantine=code.equals("INVALID_JSON");
            boolean terminal=attempt>=maxAttempts(connection,jobId);
            String status=quarantine?"QUARANTINED":terminal?"FAILED":"QUEUED";
            long delay=code.equals("RATE_LIMITED")?Math.min(3600,60L<<Math.min(attempt-1,5)):Math.min(1800,30L<<Math.min(attempt-1,5));
            try(PreparedStatement update=connection.prepareStatement("""
                    UPDATE onmaru.k_content_research_jobs SET status=?,available_at=now()+(? * interval '1 second'),
                    last_failure_code=?,lease_owner=NULL,lease_token_hash=NULL,lease_expires_at=NULL,updated_at=now() WHERE id=?
                    """)) {
                update.setString(1,status);update.setLong(2,delay);update.setString(3,code);update.setObject(4,jobId);update.executeUpdate();
            }
            receipt(connection,jobId,idempotencyKey,hash,status,worker,token);
            finishRun(connection,jobId,status.equals("QUEUED")?"RETRY":status,code);
            event(connection,jobId,"FAILURE",worker,code);
            return status;
        });
    }

    public JobStatus status(UUID jobId) {
        return tx(connection -> {
            try(PreparedStatement select=connection.prepareStatement("SELECT status,attempts,max_attempts,available_at,last_failure_code FROM onmaru.k_content_research_jobs WHERE id=?")) {
                select.setObject(1,jobId);
                try(ResultSet result=select.executeQuery()) {
                    if(!result.next()) throw new IllegalArgumentException("JOB_NOT_FOUND");
                    return new JobStatus(jobId,result.getString(1),result.getInt(2),result.getInt(3),result.getTimestamp(4).toInstant(),result.getString(5));
                }
            }
        });
    }

    public void requeue(UUID jobId,String actor) {
        tx(connection -> {
            try(PreparedStatement update=connection.prepareStatement("""
                    UPDATE onmaru.k_content_research_jobs SET status='QUEUED',attempts=0,requeue_epoch=requeue_epoch+1,available_at=now(),
                    lease_owner=NULL,lease_token_hash=NULL,lease_expires_at=NULL,updated_at=now()
                    WHERE id=? AND status IN ('FAILED','QUARANTINED')
                    """)) {
                update.setObject(1,jobId);if(update.executeUpdate()!=1) throw new IllegalStateException("JOB_NOT_REQUEUEABLE");
            }
            event(connection,jobId,"REQUEUED",actor,null);return null;
        });
    }

    private int requireLease(Connection connection,UUID jobId,String worker,String token) throws SQLException {
        try(PreparedStatement select=connection.prepareStatement("""
                SELECT attempts,lease_owner,lease_token_hash,lease_expires_at FROM onmaru.k_content_research_jobs
                WHERE id=? AND status='LEASED' FOR UPDATE
                """)) {
            select.setObject(1,jobId);
            try(ResultSet result=select.executeQuery()) {
                if(!result.next() || !worker.equals(result.getString(2)) || blank(token)
                        || !MessageDigest.isEqual(sha256(token).getBytes(StandardCharsets.US_ASCII),result.getString(3).getBytes(StandardCharsets.US_ASCII))
                        || !result.getTimestamp(4).toInstant().isAfter(Instant.now()))
                    throw new IllegalStateException("LEASE_INVALID");
                return result.getInt(1);
            }
        }
    }

    private void receipt(Connection c,UUID job,String key,String hash,String outcome,String worker,String token) throws SQLException {
        try(PreparedStatement s=c.prepareStatement("INSERT INTO onmaru.k_content_research_receipts(id,job_id,idempotency_key,request_hash,outcome,worker_id,lease_token_hash) VALUES (?,?,?,?,?,?,?)")) {
            s.setObject(1,UUID.randomUUID());s.setObject(2,job);s.setString(3,key);s.setString(4,hash);s.setString(5,outcome);s.setString(6,worker);s.setString(7,sha256(token));s.executeUpdate();
        }
    }
    private void lockJob(Connection c,UUID job) throws SQLException {
        try(PreparedStatement s=c.prepareStatement("SELECT id FROM onmaru.k_content_research_jobs WHERE id=? FOR UPDATE")) {
            s.setObject(1,job);try(ResultSet r=s.executeQuery()){if(!r.next())throw new IllegalArgumentException("JOB_NOT_FOUND");}
        }
    }
    private int maxAttempts(Connection c,UUID job) throws SQLException {
        try(PreparedStatement s=c.prepareStatement("SELECT max_attempts FROM onmaru.k_content_research_jobs WHERE id=?")) {
            s.setObject(1,job);try(ResultSet r=s.executeQuery()){r.next();return r.getInt(1);}
        }
    }
    private void finishRun(Connection c,UUID job,String outcome,String failure) throws SQLException {
        try(PreparedStatement s=c.prepareStatement("""
                UPDATE onmaru.k_content_research_runs SET finished_at=now(),outcome=?,failure_code=?
                WHERE job_id=? AND (requeue_epoch,attempt)=(SELECT requeue_epoch,attempts FROM onmaru.k_content_research_jobs WHERE id=?)
                """)) {
            s.setString(1,outcome);s.setString(2,failure);s.setObject(3,job);s.setObject(4,job);s.executeUpdate();
        }
    }
    private void event(Connection c,UUID job,String type,String actor,String detail) throws SQLException {
        try(PreparedStatement s=c.prepareStatement("INSERT INTO onmaru.k_content_research_events(id,job_id,event_type,actor,detail_code) VALUES (?,?,?,?,?)")) {
            s.setObject(1,UUID.randomUUID());s.setObject(2,job);s.setString(3,type);s.setString(4,actor);s.setString(5,detail);s.executeUpdate();
        }
    }
    private <T> T tx(SqlWork<T> work) {
        try(Connection c=dataSource.getConnection()) {
            c.setAutoCommit(false);
            try { T value=work.run(c);c.commit();return value; }
            catch(Exception exception) {c.rollback();if(exception instanceof RuntimeException runtime) throw runtime;throw new IllegalStateException("Research storage failed",exception);}
        } catch(SQLException exception) {throw new IllegalStateException("Research storage unavailable",exception);}
    }
    private interface SqlWork<T> {T run(Connection c) throws Exception;}
    private static boolean blank(String value) {return value==null || value.isBlank();}
    private static String canonicalUrl(String raw) {
        try {
            URI uri=URI.create(raw.strip()).normalize();
            if (!List.of("http","https").contains(uri.getScheme()) || uri.getHost()==null || uri.getUserInfo()!=null)
                throw new IllegalArgumentException("Invalid evidence URL");
            return new URI(uri.getScheme().toLowerCase(java.util.Locale.ROOT),null,
                    uri.getHost().toLowerCase(java.util.Locale.ROOT),uri.getPort(),
                    uri.getPath(),uri.getQuery(),null).toASCIIString();
        } catch(Exception exception) {throw new IllegalArgumentException("Invalid evidence URL",exception);}
    }
    private static String sha256(String value) {
        try {byte[] digest=MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));return java.util.HexFormat.of().formatHex(digest);}
        catch(NoSuchAlgorithmException exception) {throw new IllegalStateException(exception);}
    }
    private static Set<UUID> referencedEvidence(String json) {
        try {
            Set<UUID> references=new HashSet<>();
            collectEvidence(JSON.readTree(json),references);
            return references;
        } catch(Exception exception) {throw new IllegalArgumentException("INVALID_RESULT_JSON",exception);}
    }
    private static void collectEvidence(JsonNode node,Set<UUID> output) {
        if(node==null || node.isNull()) return;
        if(node.isArray()) {node.forEach(child->collectEvidence(child,output));return;}
        if(!node.isObject()) return;
        node.fields().forEachRemaining(entry->{
            if(entry.getKey().equals("evidenceId")) {
                if(!entry.getValue().isTextual()) throw new IllegalArgumentException("INVALID_EVIDENCE_REFERENCE");
                output.add(UUID.fromString(entry.getValue().asText()));
            } else if(entry.getKey().equals("evidenceIds")) {
                if(!entry.getValue().isArray()) throw new IllegalArgumentException("INVALID_EVIDENCE_REFERENCE");
                entry.getValue().forEach(value->{
                    if(!value.isTextual()) throw new IllegalArgumentException("INVALID_EVIDENCE_REFERENCE");
                    output.add(UUID.fromString(value.asText()));
                });
            } else collectEvidence(entry.getValue(),output);
        });
    }
}
