package com.yrootlab.onmaru.catalog.application.discoveryquery;

import com.yrootlab.onmaru.catalog.application.discoveryquery.DiscoveryQuery.Filters;
import com.yrootlab.onmaru.catalog.application.discoveryquery.DiscoveryQuery.Place;
import com.yrootlab.onmaru.catalog.application.discoveryquery.DiscoveryQuery.Relation;
import com.yrootlab.onmaru.catalog.application.discoveryquery.DiscoveryQuery.Snapshot;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class DiscoveryProjectionTests {
    @Test void uniquePlacesSelfExcludedFacetsAndTopicConstrainedRelations() {
        var drama=new Relation("r-drama","w-drama","드라마","DRAMA","FILMING_LOCATION",Set.of("ROMANCE"),Set.of("PALACE_SCENE"),Set.of());
        var movie=new Relation("r-movie","w-movie","영화","MOVIE","FILMING_LOCATION",Set.of("ACTION"),Set.of("MOVIE_SCENE"),Set.of());
        var place=new Place("p-tourapi-1",UUID.randomUUID(),"궁궐","11","서울","CORE_TRADITIONAL_PLACE",null,37.5,127.0,null,
                "HS01","HS010100",null,false,0,false,false,List.of(movie,drama));
        var other=new Place("p-tourapi-2",UUID.randomUUID(),"다른 궁궐","26","부산","CORE_TRADITIONAL_PLACE",null,35.1,129.0,null,
                "HS01","HS010100",null,false,0,false,false,List.of(drama));
        var snapshot=new Snapshot(UUID.randomUUID(),Instant.EPOCH,List.of(place,other));
        var filter=new Filters("K_DRAMA","11",null,null,null,null,Set.of(),Set.of(),null,"RELEVANCE",20);
        assertThat(DiscoveryProjection.matching(snapshot,filter,null)).containsExactly(place);
        assertThat(DiscoveryProjection.card(place,filter).get("matchingWorkCount")).isEqualTo(1L);
        assertThat(((Map<?,?>)DiscoveryProjection.card(place,filter).get("featuredRelation")).get("type")).isEqualTo("DRAMA");
        assertThat(DiscoveryProjection.facets(snapshot,filter).get("regionCode").toString()).contains("11","26");
        assertThat(DiscoveryProjection.facets(snapshot,filter).get("type").toString()).contains("DRAMA").doesNotContain("MOVIE");
        assertThat(DiscoveryProjection.facets(snapshot,filter).get("workTagCode").toString()).contains("ROMANCE").doesNotContain("ACTION");
        assertThat(DiscoveryProjection.topics(snapshot)).hasSize(6);
    }
    @Test void keysetSortDoesNotDependOnCurrentRelations() {
        var place=new Place("p-tourapi-1",UUID.randomUUID(),"궁궐","11","서울","CORE_TRADITIONAL_PLACE",null,null,null,null,
                "HS01","HS010100",null,false,0,false,false,List.of());
        var withRelation=new Place(place.id(),place.internalId(),place.name(),place.regionCode(),place.regionName(),place.role(),place.address(),
                place.latitude(),place.longitude(),place.description(),place.lclsSystm2(),place.lclsSystm3(),place.taxonomyLabel(),false,0,false,false,
                List.of(new Relation("r","w","작품","DRAMA","FILMING_LOCATION",Set.of(),Set.of(),Set.of())));
        assertThat(DiscoveryProjection.sortKey(place,"RELEVANCE")).isEqualTo(DiscoveryProjection.sortKey(withRelation,"RELEVANCE"));
    }
}
