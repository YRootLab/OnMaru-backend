package com.yrootlab.onmaru.web.kcontents;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yrootlab.onmaru.config.secrets.SecretBundle;
import com.yrootlab.onmaru.config.secrets.SecretProvider;
import com.yrootlab.onmaru.web.common.cursor.CursorCodec;
import com.yrootlab.onmaru.web.common.cursor.CursorPayload;
import com.yrootlab.onmaru.web.common.cursor.CursorSigningKey;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import com.yrootlab.onmaru.web.common.error.RequestIdFilter;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class KContentsReadPostgresTests {
    private static final GenericContainer<?> DB = new GenericContainer<>(DockerImageName.parse("postgis/postgis:17-3.5-alpine"))
            .withExposedPorts(5432).withEnv("POSTGRES_DB","onmaru_test")
            .withEnv("POSTGRES_USER","onmaru_test").withEnv("POSTGRES_PASSWORD","onmaru_test")
            .waitingFor(Wait.forLogMessage(".*database system is ready to accept connections.*\\n",2));

    @BeforeAll static void start(){DB.start();}
    @AfterAll static void stop(){DB.stop();}

    @Test void publishedIntersectionCursorRetentionRevocationAndEvidenceLimit() throws Exception {
        String url="jdbc:postgresql://"+DB.getHost()+":"+DB.getMappedPort(5432)+"/onmaru_test";
        Flyway.configure().dataSource(url,"onmaru_test","onmaru_test")
                .locations("classpath:db/migration/baseline").baselineOnMigrate(true).baselineVersion("0").load().migrate();
        var source=new DriverManagerDataSource(url,"onmaru_test","onmaru_test");
        var jdbc=new JdbcTemplate(source);
        var store=new KContentsReadStore(source);
        SecretProvider secret=name->new SecretBundle(name,"0123456789abcdef0123456789abcdef",Optional.empty());
        var codec=new KContentsCursor(new ObjectMapper(),secret,Clock.systemUTC());
        UUID firstRevision=UUID.randomUUID(), secondRevision=UUID.randomUUID();
        UUID place=UUID.randomUUID(), hiddenPlace=UUID.randomUUID(), work1=UUID.randomUUID(), work2=UUID.randomUUID();
        jdbc.update("INSERT INTO onmaru.catalog_place_identity(id,created_at) VALUES (?,now()),(?,now())",place,hiddenPlace);
        jdbc.update("INSERT INTO onmaru.catalog_place_public_ids(public_id,place_id) VALUES ('p-tourapi-1',?)",place);
        revision(jdbc,firstRevision,1);
        revision(jdbc,secondRevision,2);
        publicPlace(jdbc,firstRevision,place,"1");
        publicPlace(jdbc,secondRevision,place,"1");
        jdbc.update("INSERT INTO onmaru.selected_discovery_active(singleton,revision_id,activated_at) VALUES (true,?,now())",firstRevision);
        work(jdbc,work1,"첫 작품","first");work(jdbc,work2,"둘 작품","second");
        UUID director=UUID.randomUUID(), directorSource=UUID.randomUUID();
        jdbc.update("INSERT INTO onmaru.k_content_creative_parties(id,name,normalized_name,party_type,status) VALUES (?,'감독','감독','PERSON','HUMAN_VERIFIED')",director);
        jdbc.update("""
                INSERT INTO onmaru.k_content_metadata_sources
                (id,k_content_id,canonical_url,source_type,title,observed_at,status,verified_by,verified_at)
                VALUES (?,?,'https://official.example/credit','OFFICIAL','크레딧',now(),'VERIFIED','reviewer',now())
                """,directorSource,work1);
        jdbc.update("INSERT INTO onmaru.k_content_credits(id,k_content_id,party_id,source_id,role) VALUES (?,?,?,?, 'DIRECTOR')",
                UUID.randomUUID(),work1,director,directorSource);
        UUID relation1=relation(jdbc,place,work1), relation2=relation(jdbc,place,work2);
        relation(jdbc,hiddenPlace,work1);
        for(int n=0;n<6;n++) evidence(jdbc,relation1,"https://official.example/"+n);
        evidence(jdbc,relation2,"https://official.example/other");
        var http=MockMvcBuilders.standaloneSetup(new KContentsReadController(store,codec))
                .addFilters(new RequestIdFilter()).build();
        http.perform(get("/api/v1/k-contents/works").param("workTagCodes","palace-context"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.matchingWorkCount").value(0));
        http.perform(get("/api/v1/k-contents/works").param("q","없는작품"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items").isEmpty());
        http.perform(get("/api/v1/k-contents/works/not-a-uuid"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.details.fieldErrors.workId").exists());
        http.perform(get("/api/v1/k-contents/works").param("limit","0").header("X-Request-Id","pilot-1"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.requestId").value(com.yrootlab.onmaru.web.common.error.ExternalCorrelationId.opaque("req","pilot-1")))
                .andExpect(header().string("Cache-Control","no-store"));
        http.perform(get("/api/v1/places/p-tourapi-1/k-contents"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.matchingRelationCount").value(2))
                .andExpect(jsonPath("$.items[0].evidence").isArray())
                .andExpect(header().string("Cache-Control","no-store"));
        http.perform(get("/api/v1/places/p-tourapi-1/k-contents").param("type","MOVIE"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items").isEmpty());
        http.perform(get("/api/v1/k-contents/works/"+UUID.randomUUID()))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.details.resourceType").value("WORK"));
        http.perform(get("/api/v1/places/p-tourapi-999/k-contents"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.details.resourceType").value("PLACE"));
        http.perform(get("/api/v1/k-contents/works").param("cursor","tampered"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("CURSOR_INVALID"));
        String expired=new CursorCodec(new ObjectMapper(),CursorSigningKey.fromUtf8("0123456789abcdef0123456789abcdef"),Clock.systemUTC())
                .encode(new CursorPayload("kcontents-works-v1",Map.of("filter","unused"),Instant.EPOCH));
        http.perform(get("/api/v1/k-contents/works").param("cursor",expired))
                .andExpect(status().isGone()).andExpect(jsonPath("$.code").value("CURSOR_EXPIRED"));
        var first=store.works(firstRevision,null,null,null,null,List.of(),"RELEVANCE",1,null);
        assertThat(first.count()).isEqualTo(2);
        assertThat(first.more()).isTrue();
        assertThat(first.items().getFirst().get("workArtwork")).isNull();
        assertThat(first.items().getFirst().get("publicPlaceCount")).isEqualTo(1);
        assertThat(store.works(firstRevision,null,null,director,null,List.of(),"RELEVANCE",20,null).count()).isZero();
        assertThat(store.work(firstRevision,work1).get("artists")).isEqualTo(List.of());
        String cursor=codec.encode("kcontents-works-v1","all",firstRevision,first.lastKey(),first.lastId());
        var retained=codec.decode(cursor,"kcontents-works-v1","all");
        http.perform(get("/api/v1/k-contents/works").param("cursor",cursor))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("CURSOR_INVALID"));
        jdbc.update("UPDATE onmaru.selected_discovery_active SET revision_id=?,activated_at=now()",secondRevision);
        assertThat(store.revision(retained.revision()).id()).isEqualTo(firstRevision);
        assertThat(store.works(firstRevision,null,null,null,null,List.of(),"RELEVANCE",1,retained).items()).hasSize(1);
        assertThat(store.resolvePlace(firstRevision,"p-tourapi-1")).isEqualTo(place);
        var relations=store.relations(firstRevision,place,null,50,null);
        assertThat(relations.count()).isEqualTo(2);
        assertThat(((List<?>)relations.items().stream().filter(item->item.get("relationId").equals(relation1))
                .findFirst().orElseThrow().get("evidence")).size()).isEqualTo(5);
        jdbc.update("UPDATE onmaru.k_content_relation_evidence SET status='WITHDRAWN' WHERE relation_id=?",relation2);
        assertThat(store.relations(firstRevision,place,null,50,null).count()).isEqualTo(1);
        assertThat(store.works(firstRevision,null,null,null,null,List.of(),"RELEVANCE",50,null).count()).isEqualTo(1);
        jdbc.update("DELETE FROM onmaru.selected_discovery_approvals WHERE content_id='1'");
        assertThat(store.resolvePlace(firstRevision,"p-tourapi-1")).isNull();
        assertThat(store.works(firstRevision,null,null,null,null,List.of(),"RELEVANCE",20,null).count()).isZero();
        jdbc.update("DELETE FROM onmaru.selected_discovery_active");
        http.perform(get("/api/v1/k-contents/works"))
                .andExpect(status().isServiceUnavailable()).andExpect(header().string("Retry-After","30"))
                .andExpect(jsonPath("$.code").value("SERVICE_UNAVAILABLE"));
        var broken=new DriverManagerDataSource("jdbc:postgresql://127.0.0.1:1/missing?connectTimeout=1","missing","missing");
        var offline=MockMvcBuilders.standaloneSetup(new KContentsReadController(new KContentsReadStore(broken),codec)).build();
        offline.perform(get("/api/v1/k-contents/works"))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("SERVICE_UNAVAILABLE"));
        jdbc.execute("DROP TABLE onmaru.selected_discovery_active CASCADE");
        http.perform(get("/api/v1/k-contents/works"))
                .andExpect(status().isInternalServerError()).andExpect(jsonPath("$.code").value("INTERNAL_ERROR"));
    }

    private static void revision(JdbcTemplate jdbc,UUID id,int day){
        jdbc.update("INSERT INTO onmaru.selected_discovery_runs(id,due_at,status) VALUES (?,now()+ (? * interval '1 day'),'PUBLISHED')",id,day);
        jdbc.update("""
                INSERT INTO onmaru.selected_discovery_revisions
                (id,status,policy_version,hash_schema_version,added_count,changed_count,unchanged_count,missing_count,
                 quarantine_count,approved_count,detail_requests,counts_by_region,counts_by_role,published_at)
                VALUES (?,'PUBLISHED','v1','v1',1,0,0,0,0,1,1,'{}'::jsonb,'{}'::jsonb,now())
                """,id);
    }
    private static void publicPlace(JdbcTemplate jdbc,UUID revision,UUID place,String content){
        jdbc.update("""
                INSERT INTO onmaru.selected_discovery_candidates
                (revision_id,content_id,raw,list_hash,detail_hash,hash_schema_version,policy_version,decision,role,reason_code,diff_status)
                VALUES (?,?,'{}'::jsonb,repeat('a',64),repeat('b',64),'v1','v1','INCLUDE','PALACE','HUMAN','ADDED')
                """,revision,content);
        jdbc.update("""
                INSERT INTO onmaru.selected_discovery_approvals
                (content_id,list_hash,detail_hash,role,source_fingerprint,detail_reviewed,rights_reviewed,evidence_ref,approved_by,approved_at)
                VALUES (?,repeat('a',64),repeat('b',64),'PALACE','fp',true,true,'ref','reviewer',now())
                ON CONFLICT (content_id) DO NOTHING
                """,content);
        jdbc.update("INSERT INTO onmaru.selected_discovery_public_items(revision_id,content_id,place_id,role,raw) VALUES (?,?,?,'PALACE','{}'::jsonb)",revision,content,place);
    }
    private static void work(JdbcTemplate jdbc,UUID id,String title,String normalized){
        jdbc.update("INSERT INTO onmaru.k_contents(id,title,normalized_title,work_type,status,verified_by,verified_at) VALUES (?,?,?,'DRAMA','HUMAN_VERIFIED','reviewer',now())",id,title,normalized);
    }
    private static UUID relation(JdbcTemplate jdbc,UUID place,UUID work){
        UUID id=UUID.randomUUID();
        jdbc.update("INSERT INTO onmaru.k_content_place_relations(id,place_id,k_content_id,status,verified_by,verified_at) VALUES (?,?,?,'HUMAN_VERIFIED','reviewer',now())",id,place,work);
        return id;
    }
    private static void evidence(JdbcTemplate jdbc,UUID relation,String url){
        jdbc.update("""
                INSERT INTO onmaru.k_content_relation_evidence
                (id,relation_id,canonical_url,source_type,title,filming_excerpt,observed_at,status,verified_by,verified_at)
                VALUES (?,?,?,'OFFICIAL','출처','촬영',now(),'VERIFIED','reviewer',now())
                """,UUID.randomUUID(),relation,url);
    }
}
