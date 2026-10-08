package com.yrootlab.onmaru.web.discovery;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yrootlab.onmaru.catalog.application.discoveryquery.DiscoveryQuery.Place;
import com.yrootlab.onmaru.catalog.application.discoveryquery.DiscoveryQuery.Snapshot;
import com.yrootlab.onmaru.identity.lifecycle.MemberLifecycleService;
import com.yrootlab.onmaru.persistence.catalog.discovery.JdbcDiscoveryReadStore;
import com.yrootlab.onmaru.web.common.cursor.CursorCodec;
import com.yrootlab.onmaru.web.common.cursor.CursorSigningKey;
import com.yrootlab.onmaru.web.common.error.ApiErrorResponse;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DiscoveryControllerTests {
    private final Clock clock=Clock.fixed(Instant.parse("2026-10-09T00:00:00Z"),ZoneOffset.UTC);
    private final JdbcDiscoveryReadStore store=mock(JdbcDiscoveryReadStore.class);
    private final MemberLifecycleService members=mock(MemberLifecycleService.class);
    private final DiscoveryController controller=new DiscoveryController(store,
            new CursorCodec(new ObjectMapper(),CursorSigningKey.fromUtf8("a".repeat(40)),clock),members,clock);
    private final MockHttpServletRequest request=new MockHttpServletRequest();

    @Test void validationGuestNoStoreNotFoundAndUnavailable() {
        when(members.currentMember(null)).thenReturn(Optional.empty());
        var invalid=list("999",null,null);
        assertThat(invalid.getStatusCode().value()).isEqualTo(400);
        assertThat(((ApiErrorResponse)invalid.getBody()).code()).isEqualTo("VALIDATION_ERROR");
        assertThat(invalid.getHeaders().getCacheControl()).isEqualTo("no-store");
        assertThat(controller.place("invalid",null,request).getStatusCode().value()).isEqualTo(400);
        assertThat(controller.topics(null,request).getStatusCode().value()).isEqualTo(503);
        assertThat(controller.topics(null,request).getHeaders().getFirst("Retry-After")).isEqualTo("30");

        var snapshot=snapshot();
        when(store.load(isNull(),isNull())).thenReturn(snapshot);
        var detail=controller.place("p-tourapi-1",null,request);
        assertThat(detail.getStatusCode().value()).isEqualTo(200);
        assertThat(detail.getHeaders().getCacheControl()).isEqualTo("no-store");
        assertThat(((Map<?,?>)detail.getBody()).get("savedByMe")).isEqualTo(false);
        assertThat(((Map<?,?>)controller.place("p-tourapi-2",null,request).getBody()).get("savedByMe")).isNull();
        assertThat(controller.place("p-tourapi-missing",null,request).getStatusCode().value()).isEqualTo(404);
        var topics=controller.topics(null,request);
        assertThat(((List<?>)((Map<?,?>)topics.getBody()).get("items"))).hasSize(6);
    }
    @Test void signedCursorBindsFilterAndExpiredRevisionReturns410() {
        when(members.currentMember(null)).thenReturn(Optional.empty());
        when(store.load(isNull(),isNull())).thenReturn(snapshot());
        var first=list("1",null,null);
        assertThat(first.getStatusCode().value()).isEqualTo(200);
        var body=(Map<?,?>)first.getBody();
        assertThat(body.get("hasMore")).isEqualTo(true);
        String cursor=(String)body.get("nextCursor");
        assertThat(list("1",cursor,"11").getStatusCode().value()).isEqualTo(400);
        assertThat(((ApiErrorResponse)list("1",cursor,"11").getBody()).code()).isEqualTo("CURSOR_INVALID");
        assertThat(list("1",cursor+"x",null).getStatusCode().value()).isEqualTo(400);
        var expired=list("1",cursor,null);
        assertThat(expired.getStatusCode().value()).isEqualTo(410);
        assertThat(((ApiErrorResponse)expired.getBody()).code()).isEqualTo("CURSOR_EXPIRED");
        var retained=snapshot();
        when(store.load(any(UUID.class),isNull())).thenReturn(retained);
        var second=list("1",cursor,null);
        assertThat(second.getStatusCode().value()).isEqualTo(200);
        var firstItem=(Map<?,?>)((List<?>)body.get("items")).getFirst();
        var secondItem=(Map<?,?>)((List<?>)((Map<?,?>)second.getBody()).get("items")).getFirst();
        assertThat(secondItem.get("placeId")).isNotEqualTo(firstItem.get("placeId"));
    }
    @Test void unexpectedFailureUsesNoStoreInternalError() {
        when(members.currentMember(null)).thenReturn(Optional.empty());
        when(store.load(isNull(),isNull())).thenThrow(new NullPointerException("internal detail"));
        var response=controller.topics(null,request);
        assertThat(response.getStatusCode().value()).isEqualTo(500);
        assertThat(response.getHeaders().getCacheControl()).isEqualTo("no-store");
        assertThat(((ApiErrorResponse)response.getBody()).code()).isEqualTo("INTERNAL_ERROR");
        assertThat(((ApiErrorResponse)response.getBody()).message()).doesNotContain("internal detail");
    }
    private org.springframework.http.ResponseEntity<?> list(String limit,String cursor,String region) {
        return controller.places(null,region,null,null,null,null,null,null,null,"RELEVANCE",limit,cursor,null,request);
    }
    private Snapshot snapshot() {
        var first=new Place("p-tourapi-1",UUID.randomUUID(),"첫 장소","11","서울","CORE_TRADITIONAL_PLACE",null,null,null,null,
                "HS01","HS010100",null,false,0,true,false,List.of());
        var second=new Place("p-tourapi-2",UUID.randomUUID(),"둘째 장소","26","부산","CORE_TRADITIONAL_PLACE",null,null,null,null,
                "HS01","HS010100",null,false,0,false,false,List.of());
        return new Snapshot(UUID.randomUUID(),Instant.parse("2026-10-09T00:00:00Z"),List.of(first,second));
    }
}
