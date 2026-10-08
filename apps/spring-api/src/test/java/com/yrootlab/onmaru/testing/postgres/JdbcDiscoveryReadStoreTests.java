package com.yrootlab.onmaru.testing.postgres;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yrootlab.onmaru.catalog.application.discoveryquery.DiscoveryProjection;
import com.yrootlab.onmaru.catalog.application.discoveryquery.DiscoveryQuery.Filters;
import com.yrootlab.onmaru.persistence.catalog.discovery.JdbcDiscoveryReadStore;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

import java.sql.DriverManager;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class JdbcDiscoveryReadStoreTests {
    private static final GenericContainer<?> POSTGRES=new GenericContainer<>(DockerImageName.parse("postgis/postgis:17-3.5-alpine"))
            .withExposedPorts(5432).withEnv("POSTGRES_DB","onmaru_test").withEnv("POSTGRES_USER","onmaru_test")
            .withEnv("POSTGRES_PASSWORD","onmaru_test")
            .waitingFor(Wait.forLogMessage(".*database system is ready to accept connections.*\\n",2));
    @BeforeAll static void start(){POSTGRES.start();}
    @AfterAll static void stop(){POSTGRES.stop();}

    @Test void activeRetainedApprovalAndPublicRelations() throws Exception {
        String url="jdbc:postgresql://"+POSTGRES.getHost()+":"+POSTGRES.getMappedPort(5432)+"/onmaru_test";
        try(var connection=DriverManager.getConnection(url,"onmaru_test","onmaru_test")){PostgresTestDatabase.reset(connection);}
        Flyway.configure().dataSource(url,"onmaru_test","onmaru_test")
                .locations("classpath:db/migration/baseline").baselineOnMigrate(true).baselineVersion("0").load().migrate();
        var ds=new DriverManagerDataSource(url,"onmaru_test","onmaru_test");
        var jdbc=new JdbcTemplate(ds);
        var store=new JdbcDiscoveryReadStore(ds,new ObjectMapper(),placeId->false);
        UUID rev1=UUID.randomUUID(),rev2=UUID.randomUUID();
        UUID place=UUID.randomUUID();
        jdbc.update("INSERT INTO onmaru.catalog_place_identity(id,created_at) VALUES (?,now())",place);
        seed(jdbc,rev1,place,"123", "CORE_TRADITIONAL_PLACE");
        jdbc.update("INSERT INTO onmaru.selected_discovery_active(singleton,revision_id,activated_at) VALUES (true,?,now())",rev1);
        var first=store.load(null,null);
        assertThat(first.places()).hasSize(1);
        assertThat(first.places().getFirst().id()).isEqualTo("p-tourapi-123");
        assertThat(first.places().getFirst().topics()).contains("TRADITIONAL_SPACE_HERITAGE");
        assertThat(first.places().getFirst().lclsSystm3()).isEqualTo("HS010100");
        assertThat(first.places().getFirst().regionName()).isEqualTo("서울특별시");
        assertThat(first.places().getFirst().saveAvailable()).isFalse();
        assertThat(first.places().getFirst().savedByMe()).isFalse();
        assertThat(DiscoveryProjection.card(first.places().getFirst(),new Filters(null,null,null,null,null,null,Set.of(),Set.of(),null,"RELEVANCE",20)).get("placeImage")).isNull();
        assertThat(DiscoveryProjection.detail(first.places().getFirst()).get("images")).isEqualTo(java.util.List.of());
        UUID legacyRevision=UUID.randomUUID(),legacySource=UUID.randomUUID();
        jdbc.update("INSERT INTO onmaru.catalog_dataset_revisions(id,dataset,status,fetched_at,published_at) VALUES (?,'kto-korean-tour','PUBLISHED',now(),now())",legacyRevision);
        jdbc.update("INSERT INTO onmaru.catalog_active_datasets(dataset,revision_id,activated_at) VALUES ('kto-korean-tour',?,now())",legacyRevision);
        jdbc.update("INSERT INTO onmaru.catalog_place_sources(id,place_id,provider,dataset,external_id,language,fetched_at) VALUES (?,?,'kto-tourapi-korean','kto-korean-tour','123','ko-KR',now())",legacySource,place);
        jdbc.update("INSERT INTO onmaru.catalog_place_versions(revision_id,place_id,source_ref_id,name,category,visit_review_eligible,status,normalized_hash) VALUES (?,?,?,'기존 궁궐','HISTORIC_SITE',true,'ACTIVE',?)",
                legacyRevision,place,legacySource,"c".repeat(64));
        jdbc.update("INSERT INTO onmaru.catalog_place_public_ids(public_id,place_id) VALUES ('p-tourapi-123',?)",place);
        var supported=new JdbcDiscoveryReadStore(ds,new ObjectMapper(),id->id.equals("p-tourapi-123")).load(null,null);
        assertThat(supported.places().getFirst().saveAvailable()).isTrue();
        assertThat(jdbc.queryForObject("SELECT revision_id FROM onmaru.catalog_active_datasets WHERE dataset='kto-korean-tour'",UUID.class)).isEqualTo(legacyRevision);
        UUID audioRevision=UUID.randomUUID(),spot=UUID.randomUUID(),story=UUID.randomUUID();
        jdbc.update("INSERT INTO onmaru.catalog_dataset_revisions(id,dataset,status,fetched_at,published_at) VALUES (?,'odii-audio','PUBLISHED',now(),now())",audioRevision);
        jdbc.update("INSERT INTO onmaru.catalog_active_datasets(dataset,revision_id,activated_at) VALUES ('odii-audio',?,now())",audioRevision);
        jdbc.update("INSERT INTO onmaru.audio_odii_spots(id,provider,tid,tlid,lang_code,created_at) VALUES (?,'ODII','tid','tlid','ko-KR',now())",spot);
        jdbc.update("INSERT INTO onmaru.audio_odii_stories(id,spot_id,provider,stid,stlid,lang_code,created_at) VALUES (?,?,'ODII','stid','stlid','ko-KR',now())",story,spot);
        jdbc.update("INSERT INTO onmaru.audio_spot_versions(revision_id,spot_id,title,status,hash) VALUES (?,?,'명소','ACTIVE','hash')",audioRevision,spot);
        jdbc.update("INSERT INTO onmaru.audio_story_versions(revision_id,story_id,spot_id,title,status,hash,transcript_provenance) VALUES (?,?,?,'이야기','ACTIVE','hash','MISSING')",audioRevision,story,spot);
        jdbc.update("INSERT INTO onmaru.audio_place_odii_links(place_id,spot_id,match_method,verified_at,review_status) VALUES (?,?,'MANUAL',now(),'APPROVED')",place,spot);
        assertThat(store.load(null,null).places().getFirst().storyCount()).isEqualTo(1);
        jdbc.update("UPDATE onmaru.audio_story_versions SET status='HIDDEN' WHERE revision_id=? AND story_id=?",audioRevision,story);
        assertThat(store.load(null,null).places().getFirst().odiiLinked()).isFalse();

        UUID work=UUID.randomUUID(),relation1=UUID.randomUUID(),relation2=UUID.randomUUID();
        jdbc.update("INSERT INTO onmaru.k_contents(id,title,normalized_title,work_type,status,verified_by,verified_at) VALUES (?,'작품','작품','DRAMA','HUMAN_VERIFIED','reviewer',now())",work);
        for(UUID relation:new UUID[]{relation1,relation2}) {
            // One work can have one relation per place/type. The second relation uses a second work.
            UUID relationWork=relation.equals(relation1)?work:UUID.randomUUID();
            if(!relationWork.equals(work)) jdbc.update("INSERT INTO onmaru.k_contents(id,title,normalized_title,work_type,status,verified_by,verified_at) VALUES (?,'다른 작품','다른 작품','MOVIE','HUMAN_VERIFIED','reviewer',now())",relationWork);
            jdbc.update("INSERT INTO onmaru.k_content_place_relations(id,place_id,k_content_id,status,verified_by,verified_at) VALUES (?,?,?,'HUMAN_VERIFIED','reviewer',now())",relation,place,relationWork);
            jdbc.update("INSERT INTO onmaru.k_content_relation_evidence(id,relation_id,canonical_url,source_type,title,filming_excerpt,observed_at,status,verified_by,verified_at) VALUES (?,?,?,'OFFICIAL','공식 자료','촬영 근거',now(),'VERIFIED','reviewer',now())",
                    UUID.randomUUID(),relation,"https://example.org/"+relation);
        }
        var withRelations=store.load(null,null);
        assertThat(withRelations.places()).hasSize(1);
        assertThat(withRelations.places().getFirst().relations()).hasSize(2);
        var filters=new Filters(null,null,null,null,null,null,Set.of(),Set.of(),null,"RELEVANCE",20);
        assertThat(DiscoveryProjection.matching(withRelations,filters,null)).hasSize(1);
        assertThat(DiscoveryProjection.facets(withRelations,filters).get("type").toString()).contains("DRAMA","MOVIE","count=1");
        String stableKey=DiscoveryProjection.sortKey(withRelations.places().getFirst(),"RELEVANCE");
        jdbc.update("UPDATE onmaru.k_content_relation_evidence SET status='WITHDRAWN' WHERE relation_id=?",relation1);
        var afterWithdrawal=store.load(null,null);
        assertThat(afterWithdrawal.places()).hasSize(1);
        assertThat(afterWithdrawal.places().getFirst().relations()).hasSize(1);
        assertThat(DiscoveryProjection.sortKey(afterWithdrawal.places().getFirst(),"RELEVANCE")).isEqualTo(stableKey);
        assertThat(DiscoveryProjection.facets(afterWithdrawal,filters).get("type").toString()).doesNotContain("DRAMA");

        seed(jdbc,rev2,place,"123","CORE_TRADITIONAL_PLACE");
        jdbc.update("UPDATE onmaru.selected_discovery_active SET revision_id=?,activated_at=now()",rev2);
        assertThat(store.load(rev1,null).revision()).isEqualTo(rev1);
        jdbc.update("DELETE FROM onmaru.selected_discovery_approvals WHERE content_id='123'");
        assertThat(store.load(null,null).places()).isEmpty();
        assertThat(store.load(rev1,null).places()).isEmpty();
    }
    private static void seed(JdbcTemplate jdbc,UUID revision,UUID place,String contentId,String role) throws Exception {
        String hash="a".repeat(64),detail="b".repeat(64);
        String raw=new ObjectMapper().writeValueAsString(Map.of("provider","kto-tourapi-korean","operation","areaBasedList2",
                "fields",Map.of("contentid",contentId,"title","궁궐","areacode","11","lclsSystm3","HS010100","mapx","127.0","mapy","37.5","firstimage","https://example.org/unreviewed.jpg")));
        jdbc.update("INSERT INTO onmaru.selected_discovery_runs(id,due_at,status) VALUES (?,now()+ (? || ' seconds')::interval,'PUBLISHED')",revision,Math.abs(revision.hashCode()%100000)+1);
        jdbc.update("INSERT INTO onmaru.selected_discovery_revisions(id,status,policy_version,hash_schema_version,added_count,changed_count,unchanged_count,missing_count,quarantine_count,approved_count,detail_requests,counts_by_region,counts_by_role,published_at) VALUES (?,'PUBLISHED','test','test',1,0,0,0,0,1,0,'{}','{}',now())",revision);
        jdbc.update("INSERT INTO onmaru.selected_discovery_candidates(revision_id,content_id,raw,list_hash,detail_hash,hash_schema_version,policy_version,decision,role,reason_code,diff_status) VALUES (?,?,?::jsonb,?,?,'test','test','INCLUDE',?,'HUMAN_CONFIRMED','ADDED')",revision,contentId,raw,hash,detail,role);
        jdbc.update("INSERT INTO onmaru.selected_discovery_approvals(content_id,list_hash,detail_hash,role,source_fingerprint,detail_reviewed,rights_reviewed,evidence_ref,approved_by,approved_at) VALUES (?,?,?,?, 'fixture',true,true,'fixture','reviewer',now()) ON CONFLICT (content_id) DO NOTHING",contentId,hash,detail,role);
        jdbc.update("INSERT INTO onmaru.selected_discovery_public_items(revision_id,content_id,place_id,role,region_code,raw) VALUES (?,?,?,?,?,?::jsonb)",revision,contentId,place,role,"11",raw);
    }
}
