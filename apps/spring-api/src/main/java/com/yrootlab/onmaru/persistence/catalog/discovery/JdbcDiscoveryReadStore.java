package com.yrootlab.onmaru.persistence.catalog.discovery;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yrootlab.onmaru.catalog.application.discoveryquery.DiscoveryQuery;
import com.yrootlab.onmaru.journey.saved.place.PlaceSaveEligibility;
import com.yrootlab.onmaru.catalog.application.discoveryquery.DiscoveryQuery.Place;
import com.yrootlab.onmaru.catalog.application.discoveryquery.DiscoveryQuery.Relation;
import com.yrootlab.onmaru.catalog.application.discoveryquery.DiscoveryQuery.Snapshot;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Reads the current visible view, or a retained revision under precisely the same approval gate. */
public final class JdbcDiscoveryReadStore {
    private static final String ACTIVE = """
            SELECT p.revision_id,p.content_id,p.place_id,p.role,p.region_code,p.raw,
                   COALESCE(ids.public_id, 'p-tourapi-' || trim(both '-' from regexp_replace(lower(p.content_id),'[^a-z0-9]+','-','g'))) AS public_id,
                   region.name AS region_name,
                   EXISTS (SELECT 1 FROM onmaru.catalog_active_datasets a JOIN onmaru.catalog_place_versions v
                           ON v.revision_id=a.revision_id AND v.place_id=p.place_id AND v.status='ACTIVE'
                           WHERE a.dataset='kto-korean-tour') AS save_available,
                   EXISTS (SELECT 1 FROM onmaru.journey_saved_places s WHERE s.member_id=? AND s.place_id=ids.public_id) AS saved,
                   (SELECT count(DISTINCT story.id) FROM onmaru.audio_place_odii_links link
                    JOIN onmaru.audio_odii_stories story ON story.spot_id=link.spot_id
                    JOIN onmaru.catalog_active_datasets audio_active ON audio_active.dataset='odii-audio'
                    JOIN onmaru.audio_story_versions story_version ON story_version.revision_id=audio_active.revision_id
                       AND story_version.story_id=story.id AND story_version.status='ACTIVE'
                    WHERE link.place_id=p.place_id AND link.review_status='APPROVED') AS story_count
            FROM onmaru.selected_discovery_public_visible p
            LEFT JOIN onmaru.catalog_place_public_ids ids ON ids.place_id=p.place_id
            LEFT JOIN onmaru.map_region_display_names region ON region.provider_code=p.region_code
            ORDER BY p.place_id
            """;
    private static final String RETAINED = """
            WITH visible AS (
                SELECT p.revision_id,p.content_id,p.place_id,p.role,p.region_code,p.raw
                FROM onmaru.selected_discovery_public_items p
                JOIN onmaru.selected_discovery_candidates c ON c.revision_id=p.revision_id AND c.content_id=p.content_id
                JOIN onmaru.selected_discovery_approvals approval ON approval.content_id=p.content_id
                JOIN onmaru.selected_discovery_revisions revision ON revision.id=p.revision_id AND revision.status='PUBLISHED'
                WHERE p.revision_id=? AND c.decision='INCLUDE' AND c.diff_status<>'MISSING'
                  AND c.list_hash=approval.list_hash AND c.detail_hash=approval.detail_hash
                  AND p.role=approval.role AND approval.detail_reviewed AND approval.rights_reviewed
                  AND btrim(approval.evidence_ref)<>''
            )
            SELECT p.revision_id,p.content_id,p.place_id,p.role,p.region_code,p.raw,
                   COALESCE(ids.public_id, 'p-tourapi-' || trim(both '-' from regexp_replace(lower(p.content_id),'[^a-z0-9]+','-','g'))) AS public_id,
                   region.name AS region_name,
                   EXISTS (SELECT 1 FROM onmaru.catalog_active_datasets a JOIN onmaru.catalog_place_versions v
                           ON v.revision_id=a.revision_id AND v.place_id=p.place_id AND v.status='ACTIVE'
                           WHERE a.dataset='kto-korean-tour') AS save_available,
                   EXISTS (SELECT 1 FROM onmaru.journey_saved_places s WHERE s.member_id=? AND s.place_id=ids.public_id) AS saved,
                   (SELECT count(DISTINCT story.id) FROM onmaru.audio_place_odii_links link
                    JOIN onmaru.audio_odii_stories story ON story.spot_id=link.spot_id
                    JOIN onmaru.catalog_active_datasets audio_active ON audio_active.dataset='odii-audio'
                    JOIN onmaru.audio_story_versions story_version ON story_version.revision_id=audio_active.revision_id
                       AND story_version.story_id=story.id AND story_version.status='ACTIVE'
                    WHERE link.place_id=p.place_id AND link.review_status='APPROVED') AS story_count
            FROM visible p
            LEFT JOIN onmaru.catalog_place_public_ids ids ON ids.place_id=p.place_id
            LEFT JOIN onmaru.map_region_display_names region ON region.provider_code=p.region_code
            ORDER BY p.place_id
            """;
    private static final String RELATIONS = """
            SELECT r.id,r.place_id,r.k_content_id,w.title,w.work_type,r.relation_type,
                   COALESCE((SELECT array_agg(DISTINCT tag.code) FROM onmaru.k_content_work_tags wt
                     JOIN onmaru.k_content_tags tag ON tag.id=wt.tag_id AND tag.active
                     JOIN onmaru.k_content_metadata_sources src ON src.id=wt.source_id AND src.status='VERIFIED'
                     WHERE wt.k_content_id=w.id AND wt.status='VERIFIED'), ARRAY[]::varchar[]) AS work_tags,
                   COALESCE((SELECT array_agg(DISTINCT tag.code) FROM onmaru.k_content_relation_tags rt
                     JOIN onmaru.k_content_tags tag ON tag.id=rt.tag_id AND tag.active
                     JOIN onmaru.k_content_relation_evidence evidence ON evidence.id=rt.evidence_id AND evidence.status='VERIFIED'
                     WHERE rt.relation_id=r.id AND rt.status='VERIFIED'), ARRAY[]::varchar[]) AS relation_tags,
                   COALESCE((SELECT array_agg(DISTINCT party.id::text) FROM onmaru.k_content_credits credit
                     JOIN onmaru.k_content_creative_parties party ON party.id=credit.party_id AND party.status IN ('AUTO_VERIFIED','HUMAN_VERIFIED')
                     JOIN onmaru.k_content_metadata_sources src ON src.id=credit.source_id AND src.status='VERIFIED'
                     WHERE credit.k_content_id=w.id AND credit.role IN ('ARTIST','GROUP')), ARRAY[]::text[]) AS artist_ids
            FROM onmaru.k_content_public_relations r
            JOIN onmaru.k_contents w ON w.id=r.k_content_id
            WHERE r.place_id = ANY(?)
            ORDER BY r.place_id,r.verified_at DESC NULLS LAST,r.id
            """;

    private final DataSource dataSource;
    private final ObjectMapper mapper;
    private final PlaceSaveEligibility saveEligibility;
    public JdbcDiscoveryReadStore(DataSource dataSource, ObjectMapper mapper, PlaceSaveEligibility saveEligibility) {
        this.dataSource=dataSource; this.mapper=mapper; this.saveEligibility=saveEligibility;
    }
    public Snapshot load(UUID requestedRevision, UUID memberId) {
        try (var connection=dataSource.getConnection()) {
            connection.setReadOnly(true);
            connection.setAutoCommit(false);
            connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            try {
                UUID active=activeRevision(connection);
                if (active==null) return null;
                UUID revision=requestedRevision==null ? active : requestedRevision;
                Instant publishedAt=publishedAt(connection,revision);
                if (publishedAt==null) return null;
                var builders=new ArrayList<Builder>();
                try (var statement=connection.prepareStatement(active.equals(revision)?ACTIVE:RETAINED)) {
                    int position=1;
                    if (!active.equals(revision)) statement.setObject(position++,revision);
                    statement.setObject(position,memberId);
                    try (var result=statement.executeQuery()) {
                        while(result.next()) builders.add(read(result));
                    }
                }
                var byId=new HashMap<UUID,Builder>();
                for (var builder:builders) byId.put(builder.internalId,builder);
                if (!builders.isEmpty()) readRelations(connection,byId);
                connection.commit();
                return new Snapshot(revision,publishedAt,builders.stream().map(Builder::build).toList());
            } catch (Exception failure) { connection.rollback(); throw failure; }
        } catch (SQLException failure) { throw new IllegalStateException("Discovery read failed",failure); }
    }
    private UUID activeRevision(Connection connection) throws SQLException {
        try(var statement=connection.prepareStatement("SELECT revision_id FROM onmaru.selected_discovery_active WHERE singleton=true");var result=statement.executeQuery()) {
            return result.next()?result.getObject(1,UUID.class):null;
        }
    }
    private Instant publishedAt(Connection connection,UUID revision) throws SQLException {
        try(var statement=connection.prepareStatement("SELECT published_at FROM onmaru.selected_discovery_revisions WHERE id=? AND status='PUBLISHED'")) {
            statement.setObject(1,revision);
            try(var result=statement.executeQuery()) {return result.next()&&result.getTimestamp(1)!=null?result.getTimestamp(1).toInstant():null;}
        }
    }
    private Builder read(ResultSet row) throws SQLException {
        try {
            JsonNode fields=mapper.readTree(row.getString("raw")).path("fields");
            var b=new Builder();
            b.internalId=row.getObject("place_id",UUID.class); b.id=row.getString("public_id");
            b.name=value(fields,"title"); b.regionCode=row.getString("region_code"); b.regionName=row.getString("region_name");
            b.role=row.getString("role"); b.address=value(fields,"addr1");
            b.latitude=number(fields,"mapy"); b.longitude=number(fields,"mapx");
            b.description=value(fields,"overview"); b.lcls2=value(fields,"lclsSystm2"); b.lcls3=value(fields,"lclsSystm3");
            b.taxonomyLabel=value(fields,"lclsSystm3Name");
            b.storyCount=row.getInt("story_count"); b.saveAvailable=row.getBoolean("save_available") && saveEligibility.isSaveable(b.id); b.saved=row.getBoolean("saved");
            return b;
        } catch (java.io.IOException failure) { throw new IllegalStateException("Invalid approved source record",failure); }
    }
    private void readRelations(Connection connection,Map<UUID,Builder> builders) throws SQLException {
        try(var statement=connection.prepareStatement(RELATIONS)) {
            var ids=connection.createArrayOf("uuid",builders.keySet().toArray());
            statement.setArray(1,ids);
            try(var result=statement.executeQuery()) {
                while(result.next()) {
                    var place=builders.get(result.getObject("place_id",UUID.class));
                    if(place!=null) place.relations.add(new Relation(result.getString("id"),result.getString("k_content_id"),
                            result.getString("title"),result.getString("work_type"),result.getString("relation_type"),
                            strings(result.getArray("work_tags")),strings(result.getArray("relation_tags")),strings(result.getArray("artist_ids"))));
                }
            }
        }
    }
    private static Set<String> strings(java.sql.Array array) throws SQLException {
        var result=new HashSet<String>();
        if(array!=null) for(var item:(Object[])array.getArray()) if(item!=null) result.add(item.toString());
        return result;
    }
    private static String value(JsonNode fields,String key) {var node=fields.path(key);return node.isMissingNode()||node.isNull()||node.asText().isBlank()?null:node.asText();}
    private static Double number(JsonNode fields,String key) {try {var value=value(fields,key);return value==null?null:Double.valueOf(value);}catch(NumberFormatException ignored){return null;}}
    private static final class Builder {
        UUID internalId; String id,name,regionCode,regionName,role,address,description,lcls2,lcls3,taxonomyLabel;
        Double latitude,longitude; int storyCount; boolean saveAvailable,saved;
        List<Relation> relations=new ArrayList<>();
        Place build(){return new Place(id,internalId,name,regionCode,regionName,role,address,latitude,longitude,description,
                lcls2,lcls3,taxonomyLabel,storyCount>0,storyCount,saveAvailable,saved,List.copyOf(relations));}
    }
}
