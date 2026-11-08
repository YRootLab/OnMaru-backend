package com.yrootlab.onmaru.integration.ai.grant;

import com.yrootlab.onmaru.journey.worker.plan.AiJourneyContracts.*;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import java.util.function.Function;
import static org.assertj.core.api.Assertions.*;

class QueryGrantServiceTests {
    private final Instant now = Instant.parse("2026-11-09T00:00:00Z");
    private final MemoryStore store = new MemoryStore();
    private final QueryGrantService service = new QueryGrantService(store, Clock.fixed(now, ZoneOffset.UTC));

    @Test void issuesRandomOpaqueReferenceButStoresOnlyHashAndBoundMetadata() {
        var binding = binding();
        String token = service.issue(binding, now.plusSeconds(60));
        assertThat(token).matches("qg_[A-Za-z0-9_-]{43}");
        assertThat(store.grant.tokenHash()).matches("[a-f0-9]{64}").doesNotContain(token);
        assertThat(store.grant.expiresAt()).isEqualTo(now.plusSeconds(30));
        assertThat(store.grant.runId()).isEqualTo(binding.plan().runId());
        assertThat(store.grant.planHash()).isEqualTo(binding.plan().planHash());
        assertThat(store.grant.filters()).isEqualTo(binding.plan().proposedFilters());
        assertThat(store.grant.versions()).isEqualTo(binding.plan().versions());
        assertThat(store.grant.toString()).doesNotContain("sensitive semantic query");
        assertThat(service.issue(binding(), now.plusSeconds(5))).isNotEqualTo(token);
        assertThat(store.grant.expiresAt()).isEqualTo(now.plusSeconds(5));
    }

    @Test void rejectsScopeWideningAndDoesNotConsumeGrant() {
        String token = service.issue(binding(), now.plusSeconds(20));
        for (var scope : List.of(new QueryGrantService.LookupScope(null, "HANOK", 5),
                new QueryGrantService.LookupScope("BUSAN", "HANOK", 5),
                new QueryGrantService.LookupScope("SEOUL", null, 5),
                new QueryGrantService.LookupScope("SEOUL", "HANOK", 6))) {
            assertThatThrownBy(() -> service.lookup(token, scope, grant -> "result"))
                    .isInstanceOfSatisfying(QueryGrantException.class,
                            e -> assertThat(e.code()).isEqualTo(QueryGrantException.Code.GRANT_SCOPE_REJECTED));
        }
        assertThat(store.consumed).isFalse();
        String result = service.lookup(token, new QueryGrantService.LookupScope("SEOUL", "HANOK", 5), grant -> "result");
        assertThat(result).isEqualTo("result");
        assertThat(store.consumed).isTrue();
    }

    @Test void invalidReferenceAndExpiredDeadlineFailClosed() {
        assertThatThrownBy(() -> service.lookup("short", new QueryGrantService.LookupScope(null,null,5), g -> "x"))
                .isInstanceOf(QueryGrantException.class);
        assertThatThrownBy(() -> service.issue(binding(), now)).isInstanceOf(QueryGrantException.class);
    }

    static ApprovedPlanBinding binding() {
        var filters = new Filters("sha256:" + "b".repeat(64),true,List.of("place-1"),
                List.of("SEOUL"),List.of("HANOK"),List.of(),List.of());
        return new ApprovedPlanBinding(new PlanProposal("ai-plan.v1", "1", UUID.randomUUID(),
                Route.SQL_ONLY, RetrievalMode.NONE,List.of(),null,null,
                List.of(CanonicalField.PUBLIC_ADDRESS), filters,"sensitive semantic query",
                new Versions("corpus-1","sha256:"+"c".repeat(64),"dict-1",7),
                new Limits(0,0,0,0,5),null,"sha256:"+"a".repeat(64)));
    }

    static final class MemoryStore implements QueryGrantStore {
        QueryGrant grant;
        boolean consumed;
        public void insert(QueryGrant grant) { this.grant=grant; this.consumed=false; }
        public synchronized <T> T consume(String hash, Instant now, Function<QueryGrant,T> operation) {
            if (!grant.tokenHash().equals(hash)) throw new QueryGrantException(QueryGrantException.Code.GRANT_INVALID);
            if (consumed) throw new QueryGrantException(QueryGrantException.Code.GRANT_CONSUMED);
            T value = operation.apply(grant); consumed=true; return value;
        }
    }
}
