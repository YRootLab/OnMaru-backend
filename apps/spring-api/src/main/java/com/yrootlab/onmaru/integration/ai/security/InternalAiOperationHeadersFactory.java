package com.yrootlab.onmaru.integration.ai.security;

import com.yrootlab.onmaru.config.secrets.SecretProvider;
import com.yrootlab.onmaru.journey.worker.plan.AiJourneyContracts.CallContext;
import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Operation-specific service grants; callback capabilities are supplied separately. */
public final class InternalAiOperationHeadersFactory {
    public enum Operation {
        PLAN("journey.plan:write"), EXECUTE("journey.execute:write");
        private final String scope;
        Operation(String scope) { this.scope = scope; }
    }
    private final SecretProvider secrets;
    private final Clock clock;
    public InternalAiOperationHeadersFactory(SecretProvider secrets, Clock clock) { this.secrets = secrets; this.clock = clock; }

    public Map<String, String> headers(Operation operation, CallContext context, String revision) {
        if (context == null || context.runId() == null || context.requestId() == null
                || !context.requestId().matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")
                || context.traceId() == null || !context.traceId().matches("[0-9a-f]{32}")
                || context.traceId().equals("0".repeat(32)) || context.deadlineAt() == null)
            throw new IllegalArgumentException("invalid AI call context");
        var remaining = Duration.between(clock.instant(), context.deadlineAt());
        if (remaining.isNegative() || remaining.isZero()) throw new IllegalArgumentException("AI deadline expired");
        var ttl = remaining.compareTo(Duration.ofSeconds(30)) > 0 ? Duration.ofSeconds(30) : remaining;
        var properties = new InternalAiTokenProperties("onmaru-spring", "spring-api", "onmaru-ai",
                operation.scope, ttl, "internal-ai.service-token");
        var tokenRequest = new InternalAiTokenRequest(context.requestId(), context.traceId(), context.runId().toString(), revision, context.deadlineAt());
        String span;
        do { span = UUID.randomUUID().toString().replace("-", "").substring(0,16); }
        while (span.equals("0".repeat(16)));
        var headers = new LinkedHashMap<String,String>();
        headers.put("Authorization", "Bearer " + new InternalAiTokenSigner(secrets,properties,clock).sign(tokenRequest));
        headers.put("X-Request-Id", context.requestId());
        headers.put("X-Run-Id", context.runId().toString());
        headers.put("X-Deadline-At", context.deadlineAt().toString());
        headers.put("traceparent", "00-" + context.traceId() + "-" + span + "-01");
        return Map.copyOf(headers);
    }
}
