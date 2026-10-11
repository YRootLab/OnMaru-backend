package com.yrootlab.onmaru.catalog.application.discoveryquery;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Public, approval-gated read projection for the separate discovery catalog. */
public final class DiscoveryQuery {
    private DiscoveryQuery() {}

    public static final String VERSION = "1.2";
    public static final List<String> TOPICS = List.of(
            "TRADITIONAL_SPACE_HERITAGE", "TRADITIONAL_EXPERIENCE", "TRADITIONAL_FOOD_TEA",
            "K_DRAMA", "K_MOVIE", "K_POP_MV");
    public static final Map<String, String> TOPIC_KO = Map.of(
            TOPICS.get(0), "전통 공간·문화유산", TOPICS.get(1), "전통문화 체험",
            TOPICS.get(2), "전통 음식·차", TOPICS.get(3), "K-드라마",
            TOPICS.get(4), "K-영화", TOPICS.get(5), "K-POP 뮤직비디오");
    public static final Map<String, String> TOPIC_EN = Map.of(
            TOPICS.get(0), "Traditional Spaces & Heritage", TOPICS.get(1), "Traditional Experiences",
            TOPICS.get(2), "Traditional Food & Tea", TOPICS.get(3), "K-Drama",
            TOPICS.get(4), "K-Movie", TOPICS.get(5), "K-Pop Music Videos");
    public static final Map<String, String> ROLE_KO = Map.of(
            "CORE_TRADITIONAL_PLACE", "전통 공간·문화유산",
            "TRADITIONAL_EXPERIENCE", "전통문화 체험",
            "SURROUNDING_CULTURE", "주변 전통문화");
    public static final Set<String> WORK_TYPES = Set.of("DRAMA", "MOVIE", "VARIETY", "MUSIC_VIDEO");

    public record Relation(String id, String workId, String title, String type, String relationType,
                           Set<String> workTags, Set<String> relationTags, Set<String> artistIds) {}
    public record Place(String id, UUID internalId, String name, String regionCode, String regionName,
                        String role, String address, Double latitude, Double longitude, String description,
                        String lclsSystm2, String lclsSystm3, String taxonomyLabel,
                        boolean odiiLinked, int storyCount, boolean saveAvailable, boolean savedByMe,
                        List<Relation> relations) {
        public Set<String> topics() {
            var topics = new java.util.LinkedHashSet<String>();
            if ("CORE_TRADITIONAL_PLACE".equals(role)) topics.add(TOPICS.get(0));
            if ("TRADITIONAL_EXPERIENCE".equals(role)) topics.add(TOPICS.get(1));
            if ("SURROUNDING_CULTURE".equals(role) && lclsSystm3 != null && lclsSystm3.startsWith("FD")) topics.add(TOPICS.get(2));
            for (var relation : relations) {
                if (relation.type.equals("DRAMA")) topics.add(TOPICS.get(3));
                if (relation.type.equals("MOVIE")) topics.add(TOPICS.get(4));
                if (relation.type.equals("MUSIC_VIDEO")) topics.add(TOPICS.get(5));
            }
            return topics;
        }
    }
    public record Snapshot(UUID revision, Instant countsAsOf, List<Place> places) {}
    public record Filters(String topic, String regionCode, String placeRole, String workId, String type,
                          String artistId, Set<String> workTagCodes, Set<String> relationTagCodes,
                          Boolean odiiLinked, String sort, int limit) {}
}
