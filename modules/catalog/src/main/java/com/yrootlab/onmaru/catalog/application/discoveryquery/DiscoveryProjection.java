package com.yrootlab.onmaru.catalog.application.discoveryquery;

import com.yrootlab.onmaru.catalog.application.discoveryquery.DiscoveryQuery.Filters;
import com.yrootlab.onmaru.catalog.application.discoveryquery.DiscoveryQuery.Place;
import com.yrootlab.onmaru.catalog.application.discoveryquery.DiscoveryQuery.Relation;
import com.yrootlab.onmaru.catalog.application.discoveryquery.DiscoveryQuery.Snapshot;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Pure deterministic projection; every facet excludes its own filter only. */
public final class DiscoveryProjection {
    private DiscoveryProjection() {}
    public static List<Place> matching(Snapshot snapshot, Filters filters, String excludedDimension) {
        return snapshot.places().stream().filter(place -> matches(place,filters,excludedDimension)).toList();
    }
    private static boolean matches(Place p,Filters f,String excluded) {
        if(!"topic".equals(excluded)&&f.topic()!=null&&!p.topics().contains(f.topic())) return false;
        if(!"regionCode".equals(excluded)&&f.regionCode()!=null&&!Objects.equals(p.regionCode(),f.regionCode())) return false;
        if(!"placeRole".equals(excluded)&&f.placeRole()!=null&&!Objects.equals(p.role(),f.placeRole())) return false;
        if(f.odiiLinked()!=null&&p.odiiLinked()!=f.odiiLinked()) return false;
        return f.workId()==null&&f.type()==null&&f.artistId()==null&&f.workTagCodes().isEmpty()&&f.relationTagCodes().isEmpty()
                || p.relations().stream().anyMatch(r -> relationMatches(r,f,excluded));
    }
    private static boolean relationMatches(Relation r,Filters f,String excluded) {
        if(!"topic".equals(excluded) && f.topic()!=null) {
            String required=switch(f.topic()) {
                case "K_DRAMA" -> "DRAMA";
                case "K_MOVIE" -> "MOVIE";
                case "K_POP_MV" -> "MUSIC_VIDEO";
                default -> null;
            };
            if(required!=null&&!required.equals(r.type())) return false;
        }
        if(f.workId()!=null&&!f.workId().equals(r.workId())) return false;
        if(!"type".equals(excluded)&&f.type()!=null&&!f.type().equals(r.type())) return false;
        if(f.artistId()!=null&&!r.artistIds().contains(f.artistId())) return false;
        if(!"workTagCode".equals(excluded)&&!f.workTagCodes().isEmpty()&&java.util.Collections.disjoint(r.workTags(),f.workTagCodes())) return false;
        return "relationTagCode".equals(excluded)||f.relationTagCodes().isEmpty()||!java.util.Collections.disjoint(r.relationTags(),f.relationTagCodes());
    }
    public static String sortKey(Place place,String sort) {
        String name=place.name()==null?"":place.name().toLowerCase(java.util.Locale.ROOT);
        String prefix="NAME".equals(sort)?"":switch(place.role()) {
            case "CORE_TRADITIONAL_PLACE" -> "0|";
            case "TRADITIONAL_EXPERIENCE" -> "1|";
            default -> "2|";
        };
        return prefix+name+"|"+place.id();
    }
    public static List<Place> ordered(List<Place> places,String sort) {
        var result=new ArrayList<>(places);
        result.sort(Comparator.comparing(place->sortKey(place,sort)));
        return result;
    }
    public static Map<String,Object> facets(Snapshot snapshot,Filters filters) {
        var result=new LinkedHashMap<String,Object>();
        for(String dimension:List.of("topic","regionCode","placeRole","type","workTagCode","relationTagCode")) {
            var counts=new TreeMap<String,Long>();
            for(var place:matching(snapshot,filters,dimension)) {
                Set<String> values=switch(dimension) {
                    case "topic" -> place.topics();
                    case "regionCode" -> place.regionCode()==null?Set.of():Set.of(place.regionCode());
                    case "placeRole" -> Set.of(place.role());
                    case "type" -> place.relations().stream().filter(r->relationMatches(r,filters,dimension)).map(Relation::type).collect(Collectors.toSet());
                    case "workTagCode" -> place.relations().stream().filter(r->relationMatches(r,filters,dimension)).flatMap(r->r.workTags().stream()).collect(Collectors.toSet());
                    default -> place.relations().stream().filter(r->relationMatches(r,filters,dimension)).flatMap(r->r.relationTags().stream()).collect(Collectors.toSet());
                };
                for(String code:values) counts.merge(code,1L,Long::sum);
            }
            var entries=new ArrayList<Map<String,Object>>();
            for(var entry:counts.entrySet()) {
                var value=new LinkedHashMap<String,Object>(); value.put("code",entry.getKey());
                if(dimension.equals("regionCode")) value.put("labelKo",snapshot.places().stream().filter(p->entry.getKey().equals(p.regionCode())).map(Place::regionName).filter(Objects::nonNull).findFirst().orElse(entry.getKey()));
                if(dimension.equals("placeRole")) value.put("labelKo",DiscoveryQuery.ROLE_KO.getOrDefault(entry.getKey(),entry.getKey()));
                value.put("count",entry.getValue()); entries.add(value);
            }
            result.put(dimension,entries);
        }
        return result;
    }
    public static List<Map<String,Object>> topics(Snapshot snapshot) {
        var counts=new TreeMap<String,Long>();
        for(var place:snapshot.places()) for(var topic:place.topics()) counts.merge(topic,1L,Long::sum);
        return DiscoveryQuery.TOPICS.stream().map(code -> {
            var value=new LinkedHashMap<String,Object>(); value.put("code",code);
            value.put("labelKo",DiscoveryQuery.TOPIC_KO.get(code));value.put("labelEn",DiscoveryQuery.TOPIC_EN.get(code));
            value.put("placeCount",counts.getOrDefault(code,0L));value.put("active",counts.getOrDefault(code,0L)>0);
            return (Map<String,Object>) value;
        }).toList();
    }
    public static Map<String,Object> card(Place place,Filters filters) {
        var value=new LinkedHashMap<String,Object>();
        value.put("placeId",place.id());value.put("name",place.name());value.put("region",region(place));
        value.put("placeRoles",roles(place));value.put("topics",place.topics());value.put("placeImage",null);
        value.put("odiiLinked",place.odiiLinked());
        var relations=place.relations().stream().filter(r->relationMatches(r,filters,null)).toList();
        var featured=relations.isEmpty()?null:relations.getFirst();
        value.put("featuredRelation",featured==null?null:Map.of("relationId",featured.id(),"workId",featured.workId(),
                "workTitle",featured.title(),"type",featured.type(),"relationType",featured.relationType()));
        value.put("matchingWorkCount",relations.stream().map(Relation::workId).distinct().count());
        value.put("saveAvailable",place.saveAvailable());value.put("savedByMe",place.saveAvailable()?place.savedByMe():null);
        return value;
    }
    public static Map<String,Object> detail(Place place) {
        var value=new LinkedHashMap<String,Object>();
        value.put("schemaVersion",DiscoveryQuery.VERSION);value.put("placeId",place.id());value.put("name",place.name());
        value.put("region",region(place));value.put("address",place.address());
        value.put("coordinates",place.latitude()==null||place.longitude()==null?null:Map.of("latitude",place.latitude(),"longitude",place.longitude()));
        value.put("description",place.description());
        var source=new LinkedHashMap<String,Object>();source.put("provider","TOUR_API");source.put("lclsSystm2",place.lclsSystm2());
        source.put("lclsSystm3",place.lclsSystm3());source.put("labelKo",place.taxonomyLabel());value.put("sourceTaxonomy",source);
        value.put("placeRoles",roles(place));value.put("topics",place.topics());value.put("images",List.of());
        value.put("odii",Map.of("linked",place.odiiLinked(),"storyCount",place.storyCount()));
        value.put("kContents",Map.of("relationCount",place.relations().size(),"href","/api/v1/places/"+place.id()+"/k-contents"));
        value.put("saveAvailable",place.saveAvailable());value.put("savedByMe",place.saveAvailable()?place.savedByMe():null);
        return value;
    }
    private static Map<String,Object> region(Place place) {var value=new LinkedHashMap<String,Object>();value.put("code",place.regionCode());value.put("nameKo",place.regionName());return value;}
    private static List<Map<String,String>> roles(Place place) {return List.of(Map.of("code",place.role(),"labelKo",DiscoveryQuery.ROLE_KO.getOrDefault(place.role(),place.role())));}
}
