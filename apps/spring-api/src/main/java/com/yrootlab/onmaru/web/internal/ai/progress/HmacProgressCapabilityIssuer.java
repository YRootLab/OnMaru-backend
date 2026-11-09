package com.yrootlab.onmaru.web.internal.ai.progress;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yrootlab.onmaru.config.secrets.SecretProvider;
import com.yrootlab.onmaru.journey.worker.plan.AiJourneyContracts.ApprovedPlanBinding;
import com.yrootlab.onmaru.journey.worker.plan.JourneyPlanExecutionPorts.ProgressCapabilityIssuer;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.UUID;

final class HmacProgressCapabilityIssuer implements ProgressCapabilityIssuer {
    static final String SECRET_NAME = "internal-ai-service-token";
    static final String AUDIENCE = "spring-ai-progress";
    static final String SCOPE = "journey.run.progress.write";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();

    private final SecretProvider secrets;
    private final Clock clock;

    HmacProgressCapabilityIssuer(SecretProvider secrets, Clock clock) {
        this.secrets = secrets;
        this.clock = clock;
    }

    @Override
    public String issue(ApprovedPlanBinding binding, Instant deadline) {
        if (binding == null || binding.plan() == null || deadline == null || !clock.instant().isBefore(deadline)) {
            throw new IllegalArgumentException("active approved binding and future deadline are required");
        }
        try {
            var header = new LinkedHashMap<String, Object>();
            header.put("alg", "HS256");
            header.put("typ", "JWT");
            header.put("kid", "current");
            var claims = new LinkedHashMap<String, Object>();
            claims.put("iss", "onmaru-spring");
            claims.put("sub", "spring-api");
            claims.put("aud", AUDIENCE);
            claims.put("scope", SCOPE);
            claims.put("runId", binding.plan().runId().toString());
            claims.put("planHash", binding.plan().planHash());
            claims.put("iat", clock.instant().getEpochSecond());
            claims.put("exp", deadline.getEpochSecond());
            claims.put("jti", UUID.randomUUID().toString());
            String signingInput = encode(header) + "." + encode(claims);
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secrets.get(SECRET_NAME).current().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return signingInput + "." + B64.encodeToString(mac.doFinal(signingInput.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("progress capability could not be issued", exception);
        }
    }

    private static String encode(Object value) throws Exception {
        return B64.encodeToString(JSON.writeValueAsBytes(value));
    }
}
