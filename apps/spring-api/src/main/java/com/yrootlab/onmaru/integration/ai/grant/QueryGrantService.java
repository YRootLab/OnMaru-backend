package com.yrootlab.onmaru.integration.ai.grant;

import com.yrootlab.onmaru.journey.worker.plan.AiJourneyContracts.ApprovedPlanBinding;
import com.yrootlab.onmaru.journey.worker.plan.JourneyPlanExecutionPorts.QueryGrantIssuer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.*;
import java.util.*;
import java.util.function.Function;
import static com.yrootlab.onmaru.integration.ai.grant.QueryGrantException.Code.*;

public final class QueryGrantService implements QueryGrantIssuer {
    public record LookupScope(String regionCode, String category, int limit) { }
    private final QueryGrantStore store;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();
    public QueryGrantService(QueryGrantStore store, Clock clock) { this.store=store; this.clock=clock; }

    @Override public String issue(ApprovedPlanBinding binding, Instant deadline) {
        var plan = binding.plan();
        Instant now = clock.instant();
        if (!plan.route().requiresLookup() || !plan.proposedFilters().publicOnly())
            throw new QueryGrantException(GRANT_SCOPE_REJECTED);
        if (deadline == null || !deadline.isAfter(now)) throw new QueryGrantException(GRANT_EXPIRED);
        byte[] bytes = new byte[32]; random.nextBytes(bytes);
        String reference = "qg_" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        Instant expires = deadline.isBefore(now.plusSeconds(30)) ? deadline : now.plusSeconds(30);
        store.insert(new QueryGrant(hash(reference),plan.runId(),plan.planHash(),plan.proposedFilters(),
                plan.requestedCanonicalFields(),plan.versions(),Math.min(plan.limits().maxPlaces(),5),now,expires));
        return reference;
    }

    public <T> T lookup(String reference, LookupScope scope, Function<QueryGrant,T> operation) {
        if (reference == null || !reference.matches("qg_[A-Za-z0-9_-]{43}"))
            throw new QueryGrantException(GRANT_INVALID);
        return store.consume(hash(reference),clock.instant(),grant -> {
            if (scope.limit()<1 || scope.limit()>grant.maxPlaces()
                    || !within(scope.regionCode(),grant.filters().regionCodes())
                    || !within(scope.category(),grant.filters().categoryCodes()))
                throw new QueryGrantException(GRANT_SCOPE_REJECTED);
            return operation.apply(grant);
        });
    }

    private static boolean within(String value, List<String> allowed) {
        if (value != null && (value.isBlank() || value.length()>128)) return false;
        return allowed == null || value != null && allowed.contains(value);
    }

    public static String hash(String reference) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(reference.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException("SHA-256 unavailable"); }
    }
}
