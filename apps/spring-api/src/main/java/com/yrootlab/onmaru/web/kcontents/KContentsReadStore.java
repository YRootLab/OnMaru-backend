package com.yrootlab.onmaru.web.kcontents;

import com.yrootlab.onmaru.kcontents.canonicalization.WorkIdentity;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Component;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Reads only verified relations intersected with an operator-approved discovery revision. */
@Component
@ConditionalOnProperty(name="onmaru.discovery.api.enabled", havingValue="true")
final class KContentsReadStore {
    private static final String VISIBLE = """
            WITH visible AS (
              SELECT p.place_id FROM onmaru.selected_discovery_public_items p
              JOIN onmaru.selected_discovery_candidates c ON c.revision_id=p.revision_id AND c.content_id=p.content_id
              JOIN onmaru.selected_discovery_approvals a ON a.content_id=p.content_id
              WHERE p.revision_id=:revision AND c.decision='INCLUDE' AND c.diff_status<>'MISSING'
                AND c.list_hash=a.list_hash AND c.detail_hash=a.detail_hash AND p.role=a.role
                AND a.detail_reviewed AND a.rights_reviewed AND btrim(a.evidence_ref)<>''
            ), public_relations AS (
              SELECT r.* FROM onmaru.k_content_public_relations r JOIN visible v ON v.place_id=r.place_id
            )
            """;
    private final NamedParameterJdbcTemplate db;
    private final TransactionTemplate snapshot;

    KContentsReadStore(DataSource source) {
        this.db = new NamedParameterJdbcTemplate(source);
        this.snapshot = new TransactionTemplate(new DataSourceTransactionManager(source));
        this.snapshot.setReadOnly(true);
        this.snapshot.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }

    record Revision(UUID id, Instant at) { }
    record Page(List<Map<String,Object>> items, int count, boolean more, String lastKey, UUID lastId) { }

    Revision revision(UUID requested) {
        String sql = requested == null
                ? "SELECT r.id,r.published_at FROM onmaru.selected_discovery_active a JOIN onmaru.selected_discovery_revisions r ON r.id=a.revision_id WHERE r.status='PUBLISHED'"
                : "SELECT id,published_at FROM onmaru.selected_discovery_revisions WHERE id=:id AND status='PUBLISHED'";
        var rows = db.query(sql, new MapSqlParameterSource("id", requested), (rs,n) ->
                new Revision(rs.getObject(1,UUID.class), rs.getTimestamp(2).toInstant()));
        return rows.isEmpty() ? null : rows.getFirst();
    }

    UUID resolvePlace(UUID revision, String publicId) {
        return snapshot.execute(ignored -> resolvePlaceInSnapshot(revision, publicId));
    }

    private UUID resolvePlaceInSnapshot(UUID revision, String publicId) {
        var rows = db.query("""
                SELECT p.place_id FROM onmaru.selected_discovery_public_items p
                JOIN onmaru.selected_discovery_candidates c ON c.revision_id=p.revision_id AND c.content_id=p.content_id
                JOIN onmaru.selected_discovery_approvals a ON a.content_id=p.content_id
                LEFT JOIN onmaru.catalog_place_public_ids pid ON pid.place_id=p.place_id
                WHERE p.revision_id=:revision AND c.decision='INCLUDE' AND c.diff_status<>'MISSING'
                  AND c.list_hash=a.list_hash AND c.detail_hash=a.detail_hash AND p.role=a.role
                  AND a.detail_reviewed AND a.rights_reviewed AND btrim(a.evidence_ref)<>''
                  AND coalesce(pid.public_id,'p-tourapi-' || trim(both '-' from regexp_replace(lower(p.content_id),'[^a-z0-9]+','-','g')))=:publicId
                """, params(revision).addValue("publicId",publicId), (rs,n)->rs.getObject(1,UUID.class));
        return rows.isEmpty()?null:rows.getFirst();
    }

    boolean workExists(UUID revision, UUID work) {
        return db.queryForObject(VISIBLE + "SELECT count(*) FROM onmaru.k_contents w WHERE w.id=:work AND EXISTS (SELECT 1 FROM public_relations r WHERE r.k_content_id=w.id)",
                params(revision).addValue("work", work), Integer.class) > 0;
    }

    Page works(UUID revision, String q, String type, UUID artist, Integer year, List<String> tags,
               String sort, int limit, KContentsCursor.Position after) {
        return snapshot.execute(ignored -> worksInSnapshot(revision,q,type,artist,year,tags,sort,limit,after));
    }

    private Page worksInSnapshot(UUID revision, String q, String type, UUID artist, Integer year, List<String> tags,
                                 String sort, int limit, KContentsCursor.Position after) {
        var p = params(revision).addValue("q", q == null ? null : "%" + escape(WorkIdentity.normalize(q)) + "%")
                .addValue("type", type).addValue("artist", artist).addValue("year", year)
                .addValue("tags", tags).addValue("lastKey", after == null ? null : after.key())
                .addValue("lastId", after == null ? null : after.id()).addValue("limit", limit + 1);
        String filter = """
                FROM onmaru.k_contents w
                WHERE EXISTS (SELECT 1 FROM public_relations r WHERE r.k_content_id=w.id)
                  AND (CAST(:q AS text) IS NULL OR w.normalized_title LIKE :q ESCAPE '\\'
                       OR EXISTS (SELECT 1 FROM onmaru.k_content_aliases a WHERE a.k_content_id=w.id AND a.normalized_alias LIKE :q ESCAPE '\\'))
                  AND (CAST(:type AS text) IS NULL OR w.work_type=:type) AND (CAST(:year AS integer) IS NULL OR w.release_year=:year)
                  AND (CAST(:artist AS uuid) IS NULL OR EXISTS (SELECT 1 FROM onmaru.k_content_credits cr
                    JOIN onmaru.k_content_creative_parties party ON party.id=cr.party_id
                    JOIN onmaru.k_content_metadata_sources source ON source.id=cr.source_id
                    WHERE cr.k_content_id=w.id AND cr.party_id=:artist AND cr.role IN ('ARTIST','GROUP')
                      AND party.status IN ('AUTO_VERIFIED','HUMAN_VERIFIED') AND source.status='VERIFIED'))
                """ + (tags.isEmpty() ? "" : """
                  AND EXISTS (SELECT 1 FROM onmaru.k_content_work_tags wt JOIN onmaru.k_content_tags t ON t.id=wt.tag_id
                    JOIN onmaru.k_content_metadata_sources source ON source.id=wt.source_id
                    WHERE wt.k_content_id=w.id AND wt.status='VERIFIED' AND source.status='VERIFIED' AND t.active AND t.code IN (:tags))
                """);
        int count = db.queryForObject(VISIBLE + "SELECT count(*) " + filter, p, Integer.class);
        boolean title = "TITLE".equals(sort);
        String keyset = after == null ? "" : title
                ? " AND (w.normalized_title,w.id) > (:lastKey,:lastId)"
                : " AND w.id > :lastId";
        String order = title ? " ORDER BY w.normalized_title,w.id" : " ORDER BY w.id";
        var rows = db.query(VISIBLE + "SELECT w.id,w.title,w.normalized_title,w.work_type,w.release_year "
                + filter + keyset + order + " LIMIT :limit", p, (rs,n) -> new WorkRow(
                rs.getObject(1,UUID.class),rs.getString(2),rs.getString(3),rs.getString(4),(Integer)rs.getObject(5)));
        boolean more = rows.size() > limit;
        if (more) rows = rows.subList(0, limit);
        var items = new ArrayList<Map<String,Object>>();
        for (WorkRow row : rows) items.add(workCard(revision,row));
        WorkRow last = rows.isEmpty() ? null : rows.getLast();
        return new Page(items,count,more,last == null ? null : last.normalized(),last == null ? null : last.id());
    }

    Map<String,Object> work(UUID revision, UUID workId) {
        return snapshot.execute(ignored -> workInSnapshot(revision,workId));
    }

    private Map<String,Object> workInSnapshot(UUID revision, UUID workId) {
        var rows = db.query(VISIBLE + """
                SELECT w.id,w.title,w.normalized_title,w.work_type,w.release_year FROM onmaru.k_contents w
                WHERE w.id=:work AND EXISTS (SELECT 1 FROM public_relations r WHERE r.k_content_id=w.id)
                """, params(revision).addValue("work",workId), (rs,n) -> new WorkRow(
                rs.getObject(1,UUID.class),rs.getString(2),rs.getString(3),rs.getString(4),(Integer)rs.getObject(5)));
        if (rows.isEmpty()) return null;
        var card = workCard(revision,rows.getFirst());
        card.put("schemaVersion","1.2");
        card.put("summaryPoints", db.query("""
                SELECT summary_text FROM onmaru.k_content_work_summary_points sp
                JOIN onmaru.k_content_metadata_sources s ON s.id=sp.source_id
                WHERE sp.k_content_id=:work AND sp.status='VERIFIED' AND s.status='VERIFIED'
                ORDER BY sp.position LIMIT 3
                """, new MapSqlParameterSource("work",workId), (rs,n) -> rs.getString(1)));
        var sources = db.query("""
                SELECT title,canonical_url,source_type,publisher,observed_at FROM onmaru.k_content_metadata_sources
                WHERE k_content_id=:work AND status='VERIFIED' ORDER BY observed_at DESC,id
                """, new MapSqlParameterSource("work",workId), (rs,n) -> {
                    var source = new LinkedHashMap<String,Object>();
                    source.put("sourceTitle",rs.getString(1));source.put("sourceUrl",rs.getString(2));
                    source.put("sourceType",rs.getString(3));source.put("publisherName",rs.getString(4));
                    source.put("checkedAt",rs.getTimestamp(5).toInstant());return source;
                });
        card.put("metadataSources",sources);
        card.put("officialPageUrl",sources.stream().filter(s -> "OFFICIAL".equals(s.get("sourceType")))
                .map(s -> s.get("sourceUrl")).findFirst().orElse(null));
        card.put("placesHref","/api/v1/discovery/places?workId="+workId);
        return card;
    }

    Page relations(UUID revision, UUID place, String type, int limit, KContentsCursor.Position after) {
        return snapshot.execute(ignored -> relationsInSnapshot(revision,place,type,limit,after));
    }

    Page relationsForPublicId(UUID revision, String publicId, String type, int limit, KContentsCursor.Position after) {
        return snapshot.execute(ignored -> {
            UUID place=resolvePlaceInSnapshot(revision,publicId);
            return place==null?null:relationsInSnapshot(revision,place,type,limit,after);
        });
    }

    private Page relationsInSnapshot(UUID revision, UUID place, String type, int limit, KContentsCursor.Position after) {
        var p = params(revision).addValue("place",place).addValue("type",type)
                .addValue("lastId",after == null ? null : after.id()).addValue("limit",limit+1);
        String filter = """
                FROM public_relations r JOIN onmaru.k_contents w ON w.id=r.k_content_id
                WHERE r.place_id=:place AND (CAST(:type AS text) IS NULL OR w.work_type=:type)
                """;
        int count = db.queryForObject(VISIBLE + "SELECT count(*) " + filter,p,Integer.class);
        var rows = db.query(VISIBLE + "SELECT r.id,r.k_content_id,r.relation_type,r.verified_at,w.title,w.work_type,w.release_year "
                + filter + (after == null ? "" : " AND r.id>:lastId") + " ORDER BY r.id LIMIT :limit",p,
                (rs,n) -> new RelationRow(rs.getObject(1,UUID.class),rs.getObject(2,UUID.class),rs.getString(3),
                        rs.getTimestamp(4).toInstant(),rs.getString(5),rs.getString(6),(Integer)rs.getObject(7)));
        boolean more = rows.size()>limit;
        if (more) rows = rows.subList(0,limit);
        var items = new ArrayList<Map<String,Object>>();
        for (RelationRow row : rows) items.add(relationCard(row));
        RelationRow last = rows.isEmpty()?null:rows.getLast();
        return new Page(items,count,more,"",last==null?null:last.id());
    }

    private Map<String,Object> workCard(UUID revision, WorkRow row) {
        var result = new LinkedHashMap<String,Object>();
        result.put("workId",row.id());result.put("canonicalTitle",row.title());result.put("englishTitle",null);
        result.put("type",row.type());result.put("releaseYear",row.year());
        result.put("artists",db.query("""
                SELECT DISTINCT party.id,party.name,party.party_type FROM onmaru.k_content_credits cr
                JOIN onmaru.k_content_creative_parties party ON party.id=cr.party_id
                JOIN onmaru.k_content_metadata_sources source ON source.id=cr.source_id
                WHERE cr.k_content_id=:work AND cr.role IN ('ARTIST','GROUP')
                  AND party.status IN ('AUTO_VERIFIED','HUMAN_VERIFIED') AND source.status='VERIFIED'
                ORDER BY party.name,party.id
                """,new MapSqlParameterSource("work",row.id()),(rs,n)->Map.of(
                "artistId",rs.getObject(1,UUID.class),"name",rs.getString(2),"type",rs.getString(3))));
        result.put("workArtwork",null);
        result.put("publicPlaceCount",db.queryForObject(VISIBLE + "SELECT count(DISTINCT place_id) FROM public_relations WHERE k_content_id=:work",
                params(revision).addValue("work",row.id()),Integer.class));
        result.put("workTags",db.query("""
                SELECT DISTINCT t.code,t.label_ko FROM onmaru.k_content_work_tags wt
                JOIN onmaru.k_content_tags t ON t.id=wt.tag_id
                JOIN onmaru.k_content_metadata_sources s ON s.id=wt.source_id
                WHERE wt.k_content_id=:work AND wt.status='VERIFIED' AND s.status='VERIFIED' AND t.active
                ORDER BY t.code
                """,new MapSqlParameterSource("work",row.id()),(rs,n)->Map.of("code",rs.getString(1),"labelKo",rs.getString(2))));
        return result;
    }

    private Map<String,Object> relationCard(RelationRow row) {
        var result = new LinkedHashMap<String,Object>();
        result.put("relationId",row.id());result.put("relationType",row.type());
        var work = new LinkedHashMap<String,Object>();
        work.put("workId",row.workId());work.put("canonicalTitle",row.title());work.put("type",row.workType());
        work.put("releaseYear",row.year());result.put("work",work);
        var p = new MapSqlParameterSource("relation",row.id());
        var summaries = db.query("""
                SELECT sp.summary_text FROM onmaru.k_content_relation_summary_points sp
                JOIN onmaru.k_content_relation_evidence e ON e.id=sp.evidence_id
                WHERE sp.relation_id=:relation AND sp.status='VERIFIED' AND e.status='VERIFIED'
                ORDER BY sp.position LIMIT 1
                """,p,(rs,n)->rs.getString(1));
        result.put("contextSummary",summaries.isEmpty()?null:summaries.getFirst());
        result.put("relationTags",db.query("""
                SELECT DISTINCT t.code,t.label_ko FROM onmaru.k_content_relation_tags rt
                JOIN onmaru.k_content_tags t ON t.id=rt.tag_id
                JOIN onmaru.k_content_relation_evidence e ON e.id=rt.evidence_id
                WHERE rt.relation_id=:relation AND rt.status='VERIFIED' AND e.status='VERIFIED' AND t.active
                ORDER BY t.code
                """,p,(rs,n)->Map.of("code",rs.getString(1),"labelKo",rs.getString(2))));
        result.put("evidenceCount",db.queryForObject("SELECT count(*) FROM onmaru.k_content_relation_evidence WHERE relation_id=:relation AND status='VERIFIED'",p,Integer.class));
        result.put("evidence",db.query("""
                SELECT title,canonical_url,source_type,publisher,observed_at FROM onmaru.k_content_relation_evidence
                WHERE relation_id=:relation AND status='VERIFIED'
                ORDER BY CASE WHEN source_type='OFFICIAL' THEN 0 WHEN source_type='BROADCAST' THEN 1 ELSE 2 END,
                         verified_at DESC,id LIMIT 5
                """,p,(rs,n)->{
            var item=new LinkedHashMap<String,Object>();item.put("sourceTitle",rs.getString(1));
            item.put("sourceUrl",rs.getString(2));item.put("sourceType",rs.getString(3));
            item.put("publisherName",rs.getString(4));item.put("checkedAt",rs.getTimestamp(5).toInstant());return item;
        }));
        result.put("verifiedAt",row.verifiedAt());
        return result;
    }

    private static MapSqlParameterSource params(UUID revision) { return new MapSqlParameterSource("revision",revision); }
    private static String escape(String text) { return text.replace("\\","\\\\").replace("%","\\%").replace("_","\\_"); }
    private record WorkRow(UUID id,String title,String normalized,String type,Integer year) { }
    private record RelationRow(UUID id,UUID workId,String type,Instant verifiedAt,String title,String workType,Integer year) { }
}
