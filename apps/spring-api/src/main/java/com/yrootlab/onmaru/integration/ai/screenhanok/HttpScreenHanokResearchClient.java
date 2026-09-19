package com.yrootlab.onmaru.integration.ai.screenhanok;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yrootlab.onmaru.catalog.screenhanok.ScreenHanokCandidate;
import com.yrootlab.onmaru.catalog.screenhanok.ScreenHanokMatch;
import com.yrootlab.onmaru.catalog.screenhanok.ScreenHanokMediaType;
import com.yrootlab.onmaru.catalog.screenhanok.ScreenHanokResearchPort;
import com.yrootlab.onmaru.catalog.screenhanok.ScreenHanokResearchUnavailableException;
import com.yrootlab.onmaru.integration.ai.security.InternalAiRequestHeadersFactory;
import com.yrootlab.onmaru.integration.ai.security.InternalAiTokenRequest;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * ADR-0010: the only permitted way for Spring to obtain K-content research. Never calls an LLM
 * provider SDK directly -- this is a plain HTTP call into FastAPI's internal contract.
 */
public final class HttpScreenHanokResearchClient implements ScreenHanokResearchPort {

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final URI baseUri;
    private final InternalAiRequestHeadersFactory headersFactory;
    private final Duration timeout;

    public HttpScreenHanokResearchClient(
            HttpClient httpClient,
            ObjectMapper objectMapper,
            URI baseUri,
            InternalAiRequestHeadersFactory headersFactory,
            Duration timeout) {
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
        this.baseUri = baseUri;
        this.headersFactory = headersFactory;
        this.timeout = timeout;
    }

    @Override
    public List<ScreenHanokMatch> research(List<ScreenHanokCandidate> candidates) {
        String requestId = UUID.randomUUID().toString();
        try {
            var response = httpClient.send(httpRequest(requestId, candidates), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw unavailable("FastAPI screen-hanok research returned HTTP " + response.statusCode(), null);
            }
            return matchesFrom(response.body());
        } catch (java.net.http.HttpTimeoutException exception) {
            throw unavailable("FastAPI screen-hanok research timed out", exception);
        } catch (IOException exception) {
            throw unavailable("FastAPI screen-hanok research unreachable", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw unavailable("FastAPI screen-hanok research interrupted", exception);
        }
    }

    private HttpRequest httpRequest(String requestId, List<ScreenHanokCandidate> candidates)
            throws JsonProcessingException {
        var builder = HttpRequest.newBuilder(baseUri.resolve("/internal/v1/screen-hanok/research"))
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody(requestId, candidates)));
        String traceId = UUID.randomUUID().toString().replace("-", "");
        var headers = headersFactory.headersFor(new InternalAiTokenRequest(
                requestId,
                traceId,
                requestId,
                "screen-hanok-research",
                Instant.now().plus(timeout)));
        headers.forEach(builder::header);
        return builder.build();
    }

    private String requestBody(String requestId, List<ScreenHanokCandidate> candidates)
            throws JsonProcessingException {
        List<Map<String, String>> candidatePayload = candidates.stream()
                .map(candidate -> Map.of(
                        "placeId", candidate.placeId(),
                        "name", candidate.name(),
                        "regionName", candidate.regionName(),
                        "category", candidate.category()))
                .toList();
        return objectMapper.writeValueAsString(Map.of(
                "schemaVersion", "internal.screen-hanok.v1",
                "requestId", requestId,
                "candidates", candidatePayload));
    }

    private List<ScreenHanokMatch> matchesFrom(String body) {
        try {
            JsonNode root = objectMapper.readTree(body);
            JsonNode matchesNode = root.get("matches");
            if (matchesNode == null || !matchesNode.isArray()) {
                throw unavailable("FastAPI screen-hanok research returned an invalid contract", null);
            }
            List<ScreenHanokMatch> matches = new ArrayList<>();
            for (JsonNode match : matchesNode) {
                matches.add(new ScreenHanokMatch(
                        text(match, "placeId"),
                        ScreenHanokMediaType.valueOf(text(match, "mediaType")),
                        text(match, "workTitle"),
                        text(match, "subtitle"),
                        tags(match.get("tags")),
                        text(match, "sourceUrl"),
                        text(match, "sourceTitle")));
            }
            return matches;
        } catch (JsonProcessingException | IllegalArgumentException exception) {
            throw unavailable("FastAPI screen-hanok research returned an unparseable contract", exception);
        }
    }

    private static List<String> tags(JsonNode node) {
        if (node == null || !node.isArray()) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        node.forEach(item -> values.add(item.asText()));
        return values;
    }

    private static String text(JsonNode root, String field) {
        var node = root.get(field);
        return node == null || node.isNull() ? null : node.asText();
    }

    private static ScreenHanokResearchUnavailableException unavailable(String message, Exception cause) {
        return new ScreenHanokResearchUnavailableException(message, cause);
    }
}
