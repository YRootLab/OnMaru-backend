package com.yrootlab.onmaru.web.internal.mcp.journey;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yrootlab.onmaru.catalog.application.query.journey.ApprovedPlaceLookupItem;
import com.yrootlab.onmaru.config.secrets.SecretBundle;
import com.yrootlab.onmaru.integration.ai.grant.*;
import com.yrootlab.onmaru.journey.worker.plan.AiJourneyContracts.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.time.*;
import java.util.*;
import java.util.function.Function;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class JourneyQueryToolControllerTests {
    private MockMvc mvc;
    private String reference;
    private int lookupCalls;
    private final Instant now = Instant.parse("2026-11-09T00:00:00Z");
    private MemoryStore store;
    @BeforeEach void setup() {
        store = new MemoryStore();
        var service = new QueryGrantService(store,Clock.fixed(now,ZoneOffset.UTC));
        var filters = new Filters("sha256:"+"b".repeat(64),true,List.of("place-1"),
                List.of("SEOUL"),List.of("HANOK"),List.of(),List.of());
        reference = service.issue(new ApprovedPlanBinding(new PlanProposal("ai-plan.v1","1",UUID.randomUUID(),
                Route.SQL_ONLY,RetrievalMode.NONE,List.of(),null,null,List.of(CanonicalField.PUBLIC_ADDRESS),
                filters,null,new Versions("corpus-1","config-1","dict-1",0),new Limits(0,0,0,0,5),null,
                "sha256:"+"a".repeat(64))),now.plusSeconds(20));
        var controller = new JourneyQueryToolController((region, category, limit) -> {
            lookupCalls++;
            return List.of(new ApprovedPlaceLookupItem("place-1","서울 한옥","HANOK","서울 종로구","SEOUL",37.5,127.0),
                    new ApprovedPlaceLookupItem("outside-place","비승인","HANOK","서울","SEOUL",37.5,127.0));
        },service,name -> new SecretBundle(name,"correct-secret",Optional.of("previous-secret")));
        mvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test void authenticatesAndReturnsOnlyApprovedPlacesAndFields() throws Exception {
        mvc.perform(post(path()).header("Authorization","Bearer correct-secret")
                        .contentType(MediaType.APPLICATION_JSON).content(body("SEOUL","HANOK",5)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.catalogVersion").value("journey.query.tools.v1"))
                .andExpect(jsonPath("$.places.length()").value(1))
                .andExpect(jsonPath("$.places[0].placeId").value("place-1"))
                .andExpect(jsonPath("$.places[0].publicAddress").value("서울 종로구"))
                .andExpect(jsonPath("$.places[0].regionCode").doesNotExist())
                .andExpect(jsonPath("$.places[0].categoryCode").doesNotExist())
                .andExpect(jsonPath("$.places[0].latitude").doesNotExist());
        assertThat(lookupCalls).isEqualTo(1);
    }
    @Test void rejectsMissingAndIncorrectBearerBeforeAnyLookup() throws Exception {
        for (String auth : List.of("","Bearer wrong-secret","Basic correct-secret")) {
            mvc.perform(post(path()).header("Authorization",auth).contentType(MediaType.APPLICATION_JSON)
                            .content(body("SEOUL","HANOK",5)))
                    .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("SERVICE_AUTH_INVALID"));
        }
        assertThat(lookupCalls).isZero(); assertThat(store.consumed).isFalse();
    }
    @Test void allowsPreviousSecretAndRejectsReplay() throws Exception {
        mvc.perform(post(path()).header("Authorization","Bearer previous-secret").contentType(MediaType.APPLICATION_JSON)
                .content(body("SEOUL","HANOK",5))).andExpect(status().isOk());
        mvc.perform(post(path()).header("Authorization","Bearer correct-secret").contentType(MediaType.APPLICATION_JSON)
                .content(body("SEOUL","HANOK",5))).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("GRANT_CONSUMED"));
        assertThat(lookupCalls).isEqualTo(1);
    }
    @Test void rejectsScopeWideningWithoutConsuming() throws Exception {
        mvc.perform(post(path()).header("Authorization","Bearer correct-secret").contentType(MediaType.APPLICATION_JSON)
                .content(body("BUSAN","HANOK",5))).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("GRANT_SCOPE_REJECTED"));
        assertThat(lookupCalls).isZero(); assertThat(store.consumed).isFalse();
    }
    @Test void returnsTypedExpiryAndUnknownGrantWithoutLeakingReference() throws Exception {
        store.expired=true;
        mvc.perform(post(path()).header("Authorization","Bearer correct-secret").contentType(MediaType.APPLICATION_JSON)
                .content(body("SEOUL","HANOK",5))).andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value("GRANT_EXPIRED"));
        reference = "qg_"+"a".repeat(43);
        String response = mvc.perform(post(path()).header("Authorization","Bearer correct-secret")
                .contentType(MediaType.APPLICATION_JSON).content(body("SEOUL","HANOK",5)))
                .andExpect(status().isUnauthorized()).andReturn().getResponse().getContentAsString();
        assertThat(response).doesNotContain(reference,"correct-secret");
    }
    private String path() { return "/internal/mcp/journey/tools/journey_execute_approved_lookup"; }
    private String body(String region,String category,int limit) throws Exception {
        return new ObjectMapper().writeValueAsString(new JourneyQueryToolRequest(reference,region,category,limit));
    }
    static class MemoryStore implements QueryGrantStore {
        QueryGrant grant; boolean consumed,expired;
        public void insert(QueryGrant grant) { this.grant=grant; }
        public synchronized <T> T consume(String hash,Instant now,Function<QueryGrant,T> fn) {
            if(!grant.tokenHash().equals(hash)) throw new QueryGrantException(QueryGrantException.Code.GRANT_INVALID);
            if(expired) throw new QueryGrantException(QueryGrantException.Code.GRANT_EXPIRED);
            if(consumed) throw new QueryGrantException(QueryGrantException.Code.GRANT_CONSUMED);
            T value=fn.apply(grant); consumed=true; return value;
        }
    }
}
