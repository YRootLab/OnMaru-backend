package com.yrootlab.onmaru.integration.ai.plan;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.yrootlab.onmaru.journey.worker.AiProposalException;
import com.yrootlab.onmaru.journey.worker.WorkerDegradedReason;
import com.yrootlab.onmaru.journey.worker.plan.AiJourneyContracts.*;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.TreeMap;

/** Closed, non-coercing JSON at the AI boundary. No HTTP payload is logged. */
public final class AiPlanWireCodec {
    private final ObjectMapper mapper = new ObjectMapper()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
            .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT);

    public SafeQueryRequest readSafeQuery(byte[] body) { return read(body, SafeQueryRequest.class); }
    public PlanProposal readPlan(byte[] body) { return read(body, PlanProposal.class); }
    public ExecutionResult readResult(byte[] body) { return read(body, ExecutionResult.class); }

    public byte[] write(Object value) {
        try { return mapper.writeValueAsBytes(value); }
        catch (Exception e) { throw invalid(); }
    }

    private <T> T read(byte[] body, Class<T> type) {
        try {
            if (body.length > 262_144) throw invalid();
            return mapper.readValue(body, type);
        } catch (Exception e) { throw invalid(); }
    }

    public String planHash(PlanProposal plan) {
        ObjectNode root = mapper.valueToTree(plan);
        root.remove("planHash"); root.remove("semanticQuery");
        if (plan.semanticQuery() == null) root.putNull("semanticQueryHash");
        else root.put("semanticQueryHash", digest(plan.semanticQuery().getBytes(StandardCharsets.UTF_8)));
        return digest(write(sorted(root)));
    }

    private JsonNode sorted(JsonNode node) {
        if (node.isObject()) {
            var fields = new TreeMap<String, JsonNode>();
            node.fields().forEachRemaining(entry -> fields.put(entry.getKey(), sorted(entry.getValue())));
            ObjectNode result = mapper.createObjectNode(); fields.forEach(result::set); return result;
        }
        if (node.isArray()) { ArrayNode result = mapper.createArrayNode(); node.forEach(value -> result.add(sorted(value))); return result; }
        return node;
    }

    private static String digest(byte[] bytes) {
        try { return "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    static AiProposalException invalid() { return new AiProposalException(WorkerDegradedReason.AI_INVALID_RESPONSE); }
}
