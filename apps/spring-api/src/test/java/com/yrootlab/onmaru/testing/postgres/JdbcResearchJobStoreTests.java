package com.yrootlab.onmaru.testing.postgres;

import com.yrootlab.onmaru.kcontents.research.ResearchSubmission;
import com.yrootlab.onmaru.persistence.kcontents.JdbcResearchJobStore;
import com.yrootlab.onmaru.persistence.kcontents.JdbcResearchJobStore.Evidence;
import com.yrootlab.onmaru.web.internal.kcontents.ResearchJobController;
import com.yrootlab.onmaru.admin.auth.AdminAuthenticator;
import com.yrootlab.onmaru.admin.auth.AdminAuthenticationException;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.http.MediaType;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

import java.sql.DriverManager;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class JdbcResearchJobStoreTests {
    private static final GenericContainer<?> POSTGRES=new GenericContainer<>(DockerImageName.parse("postgis/postgis:17-3.5-alpine"))
            .withExposedPorts(5432).withEnv("POSTGRES_DB","onmaru_test").withEnv("POSTGRES_USER","onmaru_test")
            .withEnv("POSTGRES_PASSWORD","onmaru_test")
            .waitingFor(Wait.forLogMessage(".*database system is ready to accept connections.*\\n",2));
    private JdbcResearchJobStore jobs;
    private JdbcTemplate jdbc;
    private UUID place;

    @BeforeAll static void start(){POSTGRES.start();}
    @AfterAll static void stop(){POSTGRES.stop();}
    @BeforeEach void reset() throws Exception {
        String url="jdbc:postgresql://"+POSTGRES.getHost()+":"+POSTGRES.getMappedPort(5432)+"/onmaru_test";
        try(var connection=DriverManager.getConnection(url,"onmaru_test","onmaru_test")){PostgresTestDatabase.reset(connection);}
        Flyway.configure().dataSource(url,"onmaru_test","onmaru_test").locations("classpath:db/migration/baseline")
                .baselineOnMigrate(true).baselineVersion("0").load().migrate();
        var ds=new DriverManagerDataSource(url,"onmaru_test","onmaru_test");
        jobs=new JdbcResearchJobStore(ds);jdbc=new JdbcTemplate(ds);place=UUID.randomUUID();
        jdbc.update("INSERT INTO onmaru.catalog_place_identity(id,created_at) VALUES (?,now())",place);
    }

    @Test void leaseRaceAndDuplicateSubmissionConfirmOnce() throws Exception {
        UUID job=jobs.enqueue(place,"INITIAL","fingerprint-1","{}");
        assertThat(jobs.enqueue(place,"INITIAL","fingerprint-1","{}")).isEqualTo(job);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var gate=new CountDownLatch(1);
            var first=pool.submit(()->{gate.await();return jobs.lease("worker-a");});
            var second=pool.submit(()->{gate.await();return jobs.lease("worker-b");});
            gate.countDown();
            var lease=first.get()!=null?first.get():second.get();
            assertThat(lease).isNotNull();
            String owner=workerOf(job);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM onmaru.k_content_research_runs WHERE job_id=?",Integer.class,job)).isEqualTo(1);
            var submission=new ResearchSubmission("NO_MATCH","v1","p1","m1",List.of(),"{}");
            var submitGate=new CountDownLatch(1);
            var a=pool.submit(()->{submitGate.await();return jobs.submit(job,owner,lease.leaseToken(),"key-1",submission);});
            var b=pool.submit(()->{submitGate.await();return jobs.submit(job,owner,lease.leaseToken(),"key-1",submission);});
            submitGate.countDown();
            assertThat(a.get()).isEqualTo("SUCCEEDED");
            assertThat(b.get()).isEqualTo("SUCCEEDED");
            assertThatThrownBy(()->jobs.submit(job,"intruder",lease.leaseToken(),"key-1",submission)).hasMessageContaining("LEASE_INVALID");
            assertThatThrownBy(()->jobs.submit(job,owner,"wrong","key-1",submission)).hasMessageContaining("LEASE_INVALID");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM onmaru.k_content_research_receipts WHERE job_id=?",Integer.class,job)).isEqualTo(1);
        }
    }

    @Test void evidenceMustBelongToSameJobAndFailedSubmissionWritesNothing() {
        UUID job=jobs.enqueue(place,"INITIAL","f1","{}");
        UUID other=jobs.enqueue(place,"REFRESH","f2","{}");
        var lease=jobs.lease("worker");
        UUID id=jobs.addEvidence(job,"worker",lease.leaseToken(),List.of(new Evidence("https://example.org/1","출처",null,"촬영 문장"))).getFirst();
        UUID foreign=UUID.randomUUID();
        jdbc.update("INSERT INTO onmaru.k_content_research_evidence(id,job_id,canonical_url,title,excerpt) VALUES (?,?,?,?,?)",
                foreign,other,"https://example.org/2","타 작업","촬영 문장");
        var wrong=new ResearchSubmission("MATCH","v1","p1","m1",List.of(foreign),"{\"evidenceId\":\""+foreign+"\"}");
        assertThatThrownBy(()->jobs.submit(job,"worker",lease.leaseToken(),"bad",wrong)).hasMessageContaining("EVIDENCE_NOT_OWNED");
        UUID missingId=UUID.randomUUID();
        var missing=new ResearchSubmission("MATCH","v1","p1","m1",List.of(missingId),"{\"evidenceId\":\""+missingId+"\"}");
        assertThatThrownBy(()->jobs.submit(job,"worker",lease.leaseToken(),"missing",missing)).hasMessageContaining("EVIDENCE_NOT_OWNED");
        var duplicate=new ResearchSubmission("MATCH","v1","p1","m1",List.of(id,id),"{}");
        assertThatThrownBy(()->jobs.submit(job,"worker",lease.leaseToken(),"dup",duplicate)).hasMessageContaining("DUPLICATE_EVIDENCE_ID");
        var hiddenForeign=new ResearchSubmission("MATCH","v1","p1","m1",List.of(id),"{\"relations\":[{\"evidenceId\":\""+foreign+"\"}]}");
        assertThatThrownBy(()->jobs.submit(job,"worker",lease.leaseToken(),"hidden",hiddenForeign)).hasMessageContaining("EVIDENCE_REFERENCE_MISMATCH");
        assertThat(jobs.status(job).status()).isEqualTo("LEASED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM onmaru.k_content_research_receipts WHERE job_id=?",Integer.class,job)).isZero();
        assertThat(jobs.submit(job,"worker",lease.leaseToken(),"ok",new ResearchSubmission("MATCH","v1","p1","m1",List.of(id),"{\"evidenceId\":\""+id+"\"}"))).isEqualTo("SUCCEEDED");
    }

    @Test void retryLimitsAndManualRequeue() {
        UUID job=jobs.enqueue(place,"INITIAL","f1","{}");
        jdbc.update("UPDATE onmaru.k_content_research_jobs SET max_attempts=2 WHERE id=?",job);
        var one=jobs.lease("worker");
        assertThat(jobs.fail(job,"worker",one.leaseToken(),"f1","RATE_LIMITED")).isEqualTo("QUEUED");
        assertThat(jobs.fail(job,"worker",one.leaseToken(),"f1","RATE_LIMITED")).isEqualTo("QUEUED");
        jdbc.update("UPDATE onmaru.k_content_research_jobs SET available_at=now() WHERE id=?",job);
        var two=jobs.lease("worker");
        assertThat(jobs.fail(job,"worker",two.leaseToken(),"f2","CLI_FAILURE")).isEqualTo("FAILED");
        assertThat(jobs.lease("worker")).isNull();
        jobs.requeue(job,"operator");
        assertThat(jobs.status(job).status()).isEqualTo("QUEUED");
        var rerun=jobs.lease("worker");
        assertThat(rerun.jobId()).isEqualTo(job);
        assertThat(jobs.submit(job,"worker",rerun.leaseToken(),"rerun",new ResearchSubmission("NO_MATCH","v1","p1","m1",List.of(),"{}"))).isEqualTo("SUCCEEDED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM onmaru.k_content_research_runs WHERE job_id=?",Integer.class,job)).isEqualTo(3);
        UUID seven=jobs.enqueue(place,"RECHECK","f7","{}");
        jdbc.update("UPDATE onmaru.k_content_research_jobs SET max_attempts=7,attempts=6 WHERE id=?",seven);
        var finalLease=jobs.lease("worker");
        assertThat(finalLease.jobId()).isEqualTo(seven);
        assertThat(jobs.fail(seven,"worker",finalLease.leaseToken(),"f7","TIMEOUT")).isEqualTo("FAILED");
        assertThat(jobs.status(seven).attempts()).isEqualTo(7);
    }

    @Test void workerTokenIsRequiredAtApiBoundary() {
        var controller=new ResearchJobController(jobs,null,"secret");
        assertThat(controller.lease(null,"worker").getStatusCode().value()).isEqualTo(401);
        assertThat(controller.lease("Bearer wrong","worker").getStatusCode().value()).isEqualTo(401);
        assertThat(controller.lease("Bearer secret","worker").getStatusCode().value()).isEqualTo(204);
    }

    @Test void apiRejectsMissingWorkerTokenAdminEscalationAndForeignEvidence() throws Exception {
        AdminAuthenticator admins=mock(AdminAuthenticator.class);
        when(admins.authenticate("Bearer secret")).thenThrow(new AdminAuthenticationException());
        var mvc=MockMvcBuilders.standaloneSetup(new ResearchJobController(jobs,admins,"secret")).build();
        mvc.perform(post("/api/v1/internal/kcontents/research/jobs/lease").header("X-Worker-Id","worker"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/internal/kcontents/research/jobs/lease").header("Authorization","Bearer wrong")
                .header("X-Worker-Id","worker")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/internal/kcontents/research/admin/jobs").header("Authorization","Bearer secret")
                .contentType(MediaType.APPLICATION_JSON).content("{\"resultStatus\":\"NO_MATCH\",\"schemaVersion\":\"v1\",\"promptVersion\":\"p1\",\"modelVersion\":\"m1\",\"evidenceIds\":[],\"resultJson\":\"{}\"}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/internal/kcontents/research/admin/jobs/{jobId}/requeue",UUID.randomUUID())
                .header("Authorization","Bearer secret")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/internal/kcontents/research/jobs/lease").header("Authorization","Bearer admin")
                .header("X-Worker-Id","worker")).andExpect(status().isUnauthorized());
        UUID job=jobs.enqueue(place,"INITIAL","f1","{}");
        UUID other=jobs.enqueue(place,"REFRESH","f2","{}");
        var lease=jobs.lease("worker");
        mvc.perform(post("/api/v1/internal/kcontents/research/jobs/{jobId}/submit",job)
                .header("Authorization","Bearer admin").header("X-Worker-Id","worker")
                .header("X-Lease-Token",lease.leaseToken()).header("Idempotency-Key","admin-attempt")
                .contentType(MediaType.APPLICATION_JSON).content("{\"resultStatus\":\"NO_MATCH\",\"schemaVersion\":\"v1\",\"promptVersion\":\"p1\",\"modelVersion\":\"m1\",\"evidenceIds\":[],\"resultJson\":\"{}\"}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/internal/kcontents/research/jobs/{jobId}/evidence",job)
                .header("Authorization","Bearer secret").header("X-Worker-Id","intruder")
                .header("X-Lease-Token",lease.leaseToken()).contentType(MediaType.APPLICATION_JSON)
                .content("[{\"url\":\"https://example.org/x\",\"title\":\"x\",\"excerpt\":\"x\"}]"))
                .andExpect(status().isConflict());
        UUID foreign=UUID.randomUUID();
        jdbc.update("INSERT INTO onmaru.k_content_research_evidence(id,job_id,canonical_url,title,excerpt) VALUES (?,?,?,?,?)",
                foreign,other,"https://example.org/foreign","다른 작업","문장");
        String payload="{\"resultStatus\":\"MATCH\",\"schemaVersion\":\"v1\",\"promptVersion\":\"p1\",\"modelVersion\":\"m1\",\"evidenceIds\":[\""+foreign+"\"],\"resultJson\":\"{\\\"evidenceId\\\":\\\""+foreign+"\\\"}\"}";
        mvc.perform(post("/api/v1/internal/kcontents/research/jobs/{jobId}/submit",job)
                .header("Authorization","Bearer secret").header("X-Worker-Id","worker")
                .header("X-Lease-Token",lease.leaseToken()).header("Idempotency-Key","wrong-evidence")
                .contentType(MediaType.APPLICATION_JSON).content(payload)).andExpect(status().isBadRequest());
        UUID owned=jobs.addEvidence(job,"worker",lease.leaseToken(),List.of(new Evidence("https://example.org/owned","내 작업",null,"촬영 문장"))).getFirst();
        String hidden="{\"resultStatus\":\"MATCH\",\"schemaVersion\":\"v1\",\"promptVersion\":\"p1\",\"modelVersion\":\"m1\",\"evidenceIds\":[\""+owned+"\"],\"resultJson\":\"{\\\"evidenceId\\\":\\\""+foreign+"\\\"}\"}";
        mvc.perform(post("/api/v1/internal/kcontents/research/jobs/{jobId}/submit",job)
                .header("Authorization","Bearer secret").header("X-Worker-Id","worker")
                .header("X-Lease-Token",lease.leaseToken()).header("Idempotency-Key","hidden-foreign")
                .contentType(MediaType.APPLICATION_JSON).content(hidden)).andExpect(status().isBadRequest());
        assertThat(jobs.status(job).status()).isEqualTo("LEASED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM onmaru.k_content_research_receipts WHERE job_id=?",Integer.class,job)).isZero();
        jdbc.update("UPDATE onmaru.k_content_research_jobs SET lease_expires_at=now()-interval '1 second' WHERE id=?",job);
        mvc.perform(post("/api/v1/internal/kcontents/research/jobs/{jobId}/submit",job)
                .header("Authorization","Bearer secret").header("X-Worker-Id","worker")
                .header("X-Lease-Token",lease.leaseToken()).header("Idempotency-Key","expired")
                .contentType(MediaType.APPLICATION_JSON).content("{\"resultStatus\":\"NO_MATCH\",\"schemaVersion\":\"v1\",\"promptVersion\":\"p1\",\"modelVersion\":\"m1\",\"evidenceIds\":[],\"resultJson\":\"{}\"}"))
                .andExpect(status().isConflict());
    }

    private String workerOf(UUID job) {
        return jdbc.queryForObject("SELECT lease_owner FROM onmaru.k_content_research_jobs WHERE id=?",String.class,job);
    }
}
