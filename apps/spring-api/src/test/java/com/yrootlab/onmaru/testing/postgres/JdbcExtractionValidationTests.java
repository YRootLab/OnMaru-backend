package com.yrootlab.onmaru.testing.postgres;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yrootlab.onmaru.kcontents.research.ResearchSubmission;
import com.yrootlab.onmaru.kcontents.validation.ExtractionValidator;
import com.yrootlab.onmaru.kcontents.validation.ExtractionValidator.Place;
import com.yrootlab.onmaru.persistence.kcontents.JdbcExtractionValidationService;
import com.yrootlab.onmaru.persistence.kcontents.JdbcResearchJobStore;
import com.yrootlab.onmaru.persistence.kcontents.JdbcResearchJobStore.Evidence;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

import java.sql.DriverManager;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JdbcExtractionValidationTests {
    private static final GenericContainer<?> POSTGRES=new GenericContainer<>(DockerImageName.parse("postgis/postgis:17-3.5-alpine"))
            .withExposedPorts(5432).withEnv("POSTGRES_DB","onmaru_test").withEnv("POSTGRES_USER","onmaru_test")
            .withEnv("POSTGRES_PASSWORD","onmaru_test")
            .waitingFor(Wait.forLogMessage(".*database system is ready to accept connections.*\\n",2));
    private JdbcResearchJobStore jobs;
    private JdbcExtractionValidationService validation;
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
        jobs=new JdbcResearchJobStore(ds); validation=new JdbcExtractionValidationService(ds,new ObjectMapper());
        jdbc=new JdbcTemplate(ds);place=UUID.randomUUID();
        jdbc.update("INSERT INTO onmaru.catalog_place_identity(id,created_at) VALUES (?,now())",place);
        UUID region=UUID.randomUUID(), revision=UUID.randomUUID(), source=UUID.randomUUID();
        jdbc.update("INSERT INTO onmaru.catalog_regions(id,code,name,level,active) VALUES (?,?,?,'SIDO',true)",region,"11","서울특별시");
        jdbc.update("INSERT INTO onmaru.catalog_dataset_revisions(id,dataset,status,fetched_at,published_at) VALUES (?,'kto-korean-tour','PUBLISHED',now(),now())",revision);
        jdbc.update("INSERT INTO onmaru.catalog_active_datasets(dataset,revision_id,activated_at) VALUES ('kto-korean-tour',?,now())",revision);
        jdbc.update("INSERT INTO onmaru.catalog_place_sources(id,place_id,provider,dataset,external_id,language,fetched_at) VALUES (?,?,'kto-tourapi-korean','kto-korean-tour','126508','ko-KR',now())",source,place);
        jdbc.update("""
                INSERT INTO onmaru.catalog_place_versions
                (revision_id,place_id,source_ref_id,region_id,name,category,visit_review_eligible,status,normalized_hash)
                VALUES (?,?,?,?,'경복궁','HISTORIC_SITE',true,'ACTIVE','fixture')
                """,revision,place,source,region);
    }

    @Test void sameWorkAndPlaceConvergeHumanDecisionSurvivesAndEvidenceWithdrawalHidesPublicView() {
        UUID first=submit("INITIAL","source-a","https://visitkorea.go.kr/filming",true,false);
        assertThat(validation.process(first)).isTrue();
        assertThat(validation.process(first)).isFalse();
        assertThat(count("k_contents")).isEqualTo(1);
        assertThat(count("k_content_place_relations")).isEqualTo(1);
        assertThat(count("k_content_relation_evidence")).isEqualTo(1);
        assertThat(count("k_content_public_relations")).isEqualTo(1);
        jdbc.update("UPDATE onmaru.k_content_place_relations SET status='HUMAN_VERIFIED',verified_by='reviewer',verified_at=now()");
        UUID second=submit("REFRESH","source-b","https://visitkorea.go.kr/filming",true,false);
        assertThat(validation.process(second)).isTrue();
        assertThat(count("k_contents")).isEqualTo(1);
        assertThat(count("k_content_place_relations")).isEqualTo(1);
        assertThat(count("k_content_relation_evidence")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status FROM onmaru.k_content_place_relations",String.class)).isEqualTo("HUMAN_VERIFIED");
        assertThat(jdbc.queryForObject("SELECT verified_by FROM onmaru.k_content_place_relations",String.class)).isEqualTo("reviewer");
        UUID third=submit("REFRESH","source-c","https://visitkorea.go.kr/filming-2",true,false);
        assertThat(validation.process(third)).isTrue();
        assertThat(count("k_contents")).isEqualTo(1);
        assertThat(count("k_content_place_relations")).isEqualTo(1);
        assertThat(count("k_content_relation_evidence")).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT status FROM onmaru.k_content_place_relations",String.class)).isEqualTo("HUMAN_VERIFIED");
        assertThat(jdbc.queryForObject("SELECT verified_by FROM onmaru.k_content_place_relations",String.class)).isEqualTo("reviewer");
        jdbc.update("UPDATE onmaru.k_content_relation_evidence SET status='WITHDRAWN'");
        assertThat(count("k_content_public_relations")).isZero();
    }

    @Test void blogRequiresRelationReviewAndInvalidTagCannotApproveRelation() {
        UUID job=submit("INITIAL","source-a","https://example.org/post",false,true);
        assertThat(validation.process(job)).isTrue();
        assertThat(count("k_content_public_relations")).isZero();
        var queue=validation.openReviews(20);
        assertThat(queue).anyMatch(item->item.reason().equals("INDEPENDENT_SOURCE_REQUIRED"));
        assertThat(queue.stream().filter(item->item.reason().equals("TAG_EVIDENCE_OR_SCOPE_INVALID"))).hasSize(2);
        var tagReview=queue.stream().filter(item->item.reason().equals("TAG_EVIDENCE_OR_SCOPE_INVALID")).findFirst().orElseThrow();
        assertThatThrownBy(()->validation.decide(tagReview.id(),"editor",true,List.of(UUID.randomUUID())))
                .hasMessageContaining("RELATION_REVIEW_REQUIRED");
        validation.decide(tagReview.id(),"editor",false,List.of());
        assertThat(count("k_content_public_relations")).isZero();
        var relationReview=validation.openReviews(20).stream().filter(item->item.reason().equals("INDEPENDENT_SOURCE_REQUIRED")).findFirst().orElseThrow();
        UUID evidence=jdbc.queryForObject("SELECT id FROM onmaru.k_content_relation_evidence",UUID.class);
        validation.decide(relationReview.id(),"editor",true,List.of(evidence));
        assertThat(count("k_content_public_relations")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status FROM onmaru.k_content_place_relations",String.class)).isEqualTo("HUMAN_VERIFIED");
    }

    @Test void forgedOfficialUrlStaysPrivateAndWeakRescanCannotDemoteExistingAutomaticPublication() {
        UUID forged=submit("INITIAL","forged","https://visitkorea.go.kr/forged",false,false);
        assertThat(validation.process(forged)).isTrue();
        assertThat(jdbc.queryForObject("SELECT status FROM onmaru.k_content_place_relations",String.class))
                .isEqualTo("REVIEW_REQUIRED");
        assertThat(count("k_content_public_relations")).isZero();

        UUID verified=submit("REFRESH","verified","https://visitkorea.go.kr/verified",true,false);
        assertThat(validation.process(verified)).isTrue();
        assertThat(count("k_content_public_relations")).isEqualTo(1);
        UUID weak=submit("RECHECK","weak","https://example.org/blog",false,false);
        assertThat(validation.process(weak)).isTrue();
        assertThat(jdbc.queryForObject("SELECT status FROM onmaru.k_content_place_relations",String.class))
                .isEqualTo("AUTO_VERIFIED");
        assertThat(count("k_content_public_relations")).isEqualTo(1);
    }

    @Test void staleReviewCannotApproveAfterJobEpochChanges() {
        UUID job=submit("INITIAL","epoch-a","https://example.org/post",false,false);
        assertThat(validation.process(job)).isTrue();
        var review=validation.openReviews(10).stream()
                .filter(item->item.reason().equals("INDEPENDENT_SOURCE_REQUIRED")).findFirst().orElseThrow();
        UUID evidence=jdbc.queryForObject("SELECT id FROM onmaru.k_content_relation_evidence",UUID.class);
        jdbc.update("UPDATE onmaru.k_content_research_jobs SET requeue_epoch=requeue_epoch+1 WHERE id=?",job);
        assertThatThrownBy(()->validation.decide(review.id(),"editor",true,List.of(evidence)))
                .hasMessageContaining("REVIEW_STALE");
        assertThat(count("k_content_public_relations")).isZero();
    }

    @Test void noMatchIsRecordedWithoutReviewOrPublicRelation() {
        UUID job=jobs.enqueue(place,"INITIAL","no-match","{\"placeTitle\":\"경복궁\",\"region\":\"서울\"}");
        var lease=jobs.lease("worker");
        jobs.submit(job,"worker",lease.leaseToken(),"no-match-submit",
                new ResearchSubmission("NO_MATCH",ExtractionValidator.SCHEMA_VERSION,
                        ExtractionValidator.PROMPT_VERSION,"fixture-model",List.of(),
                        "{\"resultStatus\":\"NO_MATCH\",\"candidates\":[],\"nextSearchAt\":\"2026-11-01T00:00:00Z\"}"));
        assertThat(validation.process(job)).isTrue();
        assertThat(jdbc.queryForObject("SELECT status FROM onmaru.k_content_validation_runs WHERE job_id=?",String.class,job))
                .isEqualTo("NO_MATCH");
        assertThat(validation.openReviews(10)).isEmpty();
        assertThat(count("k_content_public_relations")).isZero();
    }

    @Test void workerExtractionFixtureMatchesServerContract() throws Exception {
        try (var stream=getClass().getResourceAsStream("/fixtures/kcontents/w7-submission.json")) {
            var fixture=new ObjectMapper().readTree(stream);
            var source=fixture.path("evidence").get(0);
            var submission=fixture.path("submission");
            UUID fixturePlace=UUID.fromString(fixture.path("placeId").asText());
            UUID fixtureEvidence=UUID.fromString(source.path("evidenceId").asText());
            var report=new ExtractionValidator().validate(fixturePlace,fixture.path("sourceFingerprint").asText(),
                    submission.path("schemaVersion").asText(),submission.path("promptVersion").asText(),
                    submission.path("modelVersion").asText(),submission.path("resultStatus").asText(),
                    submission.path("resultJson").asText(),new Place(fixturePlace,"경복궁","서울 종로구"),
                    List.of(new ExtractionValidator.Evidence(fixtureEvidence,source.path("url").asText(),
                            source.path("title").asText(),source.path("publisher").asText(),
                            source.path("excerpt").asText(),false)));
            assertThat(report.status()).isEqualTo("REVIEW_REQUIRED");
            assertThat(report.candidates().getFirst().reason()).isEqualTo("INDEPENDENT_SOURCE_REQUIRED");
            UUID revision=jdbc.queryForObject("SELECT revision_id FROM onmaru.catalog_active_datasets WHERE dataset='kto-korean-tour'",UUID.class);
            UUID region=jdbc.queryForObject("SELECT id FROM onmaru.catalog_regions WHERE code='11'",UUID.class);
            UUID catalogSource=UUID.randomUUID();
            jdbc.update("INSERT INTO onmaru.catalog_place_identity(id,created_at) VALUES (?,now())",fixturePlace);
            jdbc.update("INSERT INTO onmaru.catalog_place_sources(id,place_id,provider,dataset,external_id,language,fetched_at) VALUES (?,?,'kto-tourapi-korean','kto-korean-tour','fixture-w7','ko-KR',now())",catalogSource,fixturePlace);
            jdbc.update("""
                    INSERT INTO onmaru.catalog_place_versions
                    (revision_id,place_id,source_ref_id,region_id,name,category,address,visit_review_eligible,status,normalized_hash)
                    VALUES (?,?,?,?,'경복궁','HISTORIC_SITE','서울특별시 종로구',true,'ACTIVE','fixture-w7')
                    """,revision,fixturePlace,catalogSource,region);
            UUID job=jobs.enqueue(fixturePlace,"WORKER_FIXTURE",fixture.path("sourceFingerprint").asText(),
                    fixture.path("inputJson").toString());
            var lease=jobs.lease("worker");
            assertThat(lease.jobId()).isEqualTo(job);
            jdbc.update("""
                    INSERT INTO onmaru.k_content_research_evidence
                    (id,job_id,canonical_url,title,publisher,excerpt)
                    VALUES (?,?,?,?,?,?)
                    """,fixtureEvidence,job,source.path("url").asText(),source.path("title").asText(),
                    source.path("publisher").asText(),source.path("excerpt").asText());
            jobs.submit(job,"worker",lease.leaseToken(),"fixture-submit",
                    new ResearchSubmission(submission.path("resultStatus").asText(),
                            submission.path("schemaVersion").asText(),submission.path("promptVersion").asText(),
                            submission.path("modelVersion").asText(),List.of(fixtureEvidence),submission.path("resultJson").asText()));
            assertThat(validation.process(job)).isTrue();
            assertThat(jdbc.queryForObject("SELECT status FROM onmaru.k_content_validation_runs WHERE job_id=?",String.class,job))
                    .isEqualTo("REVIEW_REQUIRED");
            assertThat(count("k_content_place_relations")).isEqualTo(1);
            assertThat(count("k_content_public_relations")).isZero();
        }
    }

    @Test void oneResearchSourceCanSupportTwoDistinctRelations() {
        UUID job=jobs.enqueue(place,"MULTI_WORK","multi-1","{\"title\":\"경복궁\",\"region\":\"서울\"}");
        var lease=jobs.lease("worker");
        String quote="서울 경복궁에서 드라마 별빛과 달빛을 촬영했다";
        UUID source=jobs.addEvidence(job,"worker",lease.leaseToken(),
                List.of(new Evidence("https://visitkorea.go.kr/two","공식 촬영지","한국관광공사",quote))).getFirst();
        jdbc.update("UPDATE onmaru.k_content_research_evidence SET source_verified_at=now(),source_verified_by='server-fixture' WHERE id=?",source);
        String candidate="{\"title\":\"%s\",\"workType\":\"DRAMA\",\"placeId\":\"%s\",\"placeName\":\"경복궁\",\"region\":\"서울\",\"relationType\":\"FILMING_LOCATION\",\"evidence\":[{\"evidenceId\":\"%s\",\"quote\":\"%s\"}],\"tags\":[],\"summaries\":[]}";
        String result="{\"resultStatus\":\"MATCH\",\"candidates\":["
                +candidate.formatted("별빛",place,source,quote)+","+candidate.formatted("달빛",place,source,quote)+"]}";
        jobs.submit(job,"worker",lease.leaseToken(),"multi-submit",
                new ResearchSubmission("MATCH",ExtractionValidator.SCHEMA_VERSION,
                        ExtractionValidator.PROMPT_VERSION,"fixture-model",List.of(source),result));
        assertThat(validation.process(job)).isTrue();
        assertThat(count("k_contents")).isEqualTo(2);
        assertThat(count("k_content_place_relations")).isEqualTo(2);
        assertThat(count("k_content_relation_evidence")).isEqualTo(2);
        assertThat(count("k_content_public_relations")).isEqualTo(2);
    }

    @Test void citedAliasKnownTagAndSupportedSummariesAreNormalized() {
        UUID tag=UUID.randomUUID();
        jdbc.update("INSERT INTO onmaru.k_content_tags(id,code,scope,group_code,label_ko,active,policy_version) VALUES (?,'palace-context','RELATION_TAG','RELATION_CONTEXT','궁중',true,'v1')",tag);
        jdbc.update("INSERT INTO onmaru.k_content_tag_aliases(id,tag_id,scope,group_code,alias_text,normalized_alias) VALUES (?,?,'RELATION_TAG','RELATION_CONTEXT','궁중','궁중')",UUID.randomUUID(),tag);
        UUID job=jobs.enqueue(place,"ALIAS_TAG","alias-tag-a","{\"title\":\"경복궁\",\"region\":\"서울\"}");
        var lease=jobs.lease("worker");
        String quote="서울 경복궁에서 드라마 별빛(Star Light)을 촬영했다";
        String excerpt=quote+". 궁중";
        UUID source=jobs.addEvidence(job,"worker",lease.leaseToken(),
                List.of(new Evidence("https://visitkorea.go.kr/alias","공식 촬영지","한국관광공사",excerpt))).getFirst();
        jdbc.update("UPDATE onmaru.k_content_research_evidence SET source_verified_at=now(),source_verified_by='server-fixture' WHERE id=?",source);
        String result="{\"resultStatus\":\"MATCH\",\"candidates\":[{\"title\":\"별빛\",\"workType\":\"DRAMA\","
                +"\"aliases\":[\"Star Light\"],\"placeId\":\""+place+"\",\"placeName\":\"경복궁\",\"region\":\"서울\","
                +"\"relationType\":\"FILMING_LOCATION\",\"evidence\":[{\"evidenceId\":\""+source+"\",\"quote\":\""+quote+"\"}],"
                +"\"tags\":[{\"rawLabel\":\"궁중\",\"scope\":\"RELATION_TAG\",\"groupHint\":\"RELATION_CONTEXT\",\"evidenceId\":\""+source+"\",\"quote\":\"궁중\"}],"
                +"\"summaries\":[{\"scope\":\"WORK\",\"text\":\"드라마 별빛\",\"evidenceId\":\""+source+"\",\"quote\":\""+quote+"\"},{\"scope\":\"RELATION\",\"text\":\"서울 경복궁\",\"evidenceId\":\""+source+"\",\"quote\":\""+quote+"\"}]}]}";
        jobs.submit(job,"worker",lease.leaseToken(),"alias-tag-submit",
                new ResearchSubmission("MATCH",ExtractionValidator.SCHEMA_VERSION,
                        ExtractionValidator.PROMPT_VERSION,"fixture-model",List.of(source),result));
        assertThat(validation.process(job)).isTrue();
        assertThat(count("k_content_aliases")).isEqualTo(1);
        assertThat(count("k_content_relation_tags")).isEqualTo(1);
        assertThat(count("k_content_relation_summary_points")).isEqualTo(1);
        assertThat(count("k_content_work_summary_points")).isEqualTo(1);
        assertThat(count("k_content_tag_candidates")).isZero();
    }

    private UUID submit(String reason,String fingerprint,String url,boolean official,boolean badTag) {
        UUID job=jobs.enqueue(place,reason,fingerprint,"{\"title\":\"경복궁\",\"regionName\":\"서울\"}");
        var lease=jobs.lease("worker");
        assertThat(lease.jobId()).isEqualTo(job);
        String quote="서울 경복궁에서 드라마 별빛을 촬영했다";
        UUID evidence=jobs.addEvidence(job,"worker",lease.leaseToken(),
                List.of(new Evidence(url,"촬영 기사",official?"한국관광공사":"개인 블로그",quote))).getFirst();
        if (official) jdbc.update("UPDATE onmaru.k_content_research_evidence SET source_verified_at=now(),source_verified_by='server-fixture' WHERE id=?",evidence);
        String tags=badTag?"[{\"rawLabel\":\"로맨스\",\"scope\":\"RELATION_TAG\",\"groupHint\":\"RELATION_CONTEXT\",\"evidenceId\":\""+evidence+"\",\"quote\":\"없는 표현\"},{\"rawLabel\":\"복식\",\"scope\":\"RELATION_TAG\",\"groupHint\":\"RELATION_CONTEXT\",\"evidenceId\":\""+evidence+"\",\"quote\":\"또 없는 표현\"}]":"[]";
        String result="{\"resultStatus\":\"MATCH\",\"candidates\":[{\"title\":\"별빛\",\"workType\":\"DRAMA\","
                +"\"placeId\":\""+place+"\",\"placeName\":\"경복궁\",\"region\":\"서울\","
                +"\"relationType\":\"FILMING_LOCATION\",\"evidence\":[{\"evidenceId\":\""+evidence+"\",\"quote\":\""+quote+"\"}],"
                +"\"tags\":"+tags+",\"summaries\":[]}] }";
        jobs.submit(job,"worker",lease.leaseToken(),"submit-"+fingerprint,
                new ResearchSubmission("MATCH",ExtractionValidator.SCHEMA_VERSION,
                        ExtractionValidator.PROMPT_VERSION,"fixture-model",List.of(evidence),result));
        return job;
    }
    private int count(String table) {return jdbc.queryForObject("SELECT count(*) FROM onmaru."+table,Integer.class);}
}
