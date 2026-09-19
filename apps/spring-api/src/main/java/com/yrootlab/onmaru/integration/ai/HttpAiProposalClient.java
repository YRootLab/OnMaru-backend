package com.yrootlab.onmaru.integration.ai;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yrootlab.onmaru.integration.ai.security.InternalAiRequestHeadersFactory;
import com.yrootlab.onmaru.integration.ai.security.InternalAiTokenRequest;
import com.yrootlab.onmaru.journey.worker.AiProposalClient;
import com.yrootlab.onmaru.journey.worker.AiProposalException;
import com.yrootlab.onmaru.journey.worker.CandidatePayload;
import com.yrootlab.onmaru.journey.worker.JourneyWorkerPlan;
import com.yrootlab.onmaru.journey.worker.JourneyWorkerRequest;
import com.yrootlab.onmaru.journey.worker.JourneyWorkerTelemetry;
import com.yrootlab.onmaru.journey.worker.WorkerDegradedReason;
import com.yrootlab.onmaru.journey.worker.WorkerTelemetryEvent;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class HttpAiProposalClient implements AiProposalClient {

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final URI baseUri;
    private final InternalAiRequestHeadersFactory headersFactory;
    private final Duration timeout;
    private final JourneyWorkerTelemetry telemetry;

    public HttpAiProposalClient(
            HttpClient httpClient,
            ObjectMapper objectMapper,
            URI baseUri,
            InternalAiRequestHeadersFactory headersFactory,
            Duration timeout,
            JourneyWorkerTelemetry telemetry) {
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
        this.baseUri = baseUri;
        this.headersFactory = headersFactory;
        this.timeout = timeout;
        this.telemetry = telemetry;
    }

    @Override
    public JourneyWorkerPlan propose(JourneyWorkerRequest request, CandidatePayload payload) {
        try {
            var response = httpClient.send(httpRequest(request, payload), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 429) {
                throw degraded(request, WorkerDegradedReason.AI_QUOTA_EXCEEDED, response.statusCode());
            }
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw degraded(request, WorkerDegradedReason.AI_SERVICE_UNAVAILABLE, response.statusCode());
            }
            var plan = planFrom(request, response.body());
            record(request, "SUCCESS", response.statusCode(), null);
            return plan;
        } catch (java.net.http.HttpTimeoutException exception) {
            throw degraded(request, WorkerDegradedReason.AI_TIMEOUT, null);
        } catch (IOException exception) {
            throw degraded(request, WorkerDegradedReason.AI_SERVICE_UNAVAILABLE, null);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw degraded(request, WorkerDegradedReason.AI_SERVICE_UNAVAILABLE, null);
        }
    }

    private HttpRequest httpRequest(JourneyWorkerRequest request, CandidatePayload payload)
            throws JsonProcessingException {
        var builder = HttpRequest.newBuilder(baseUri.resolve("/internal/v1/journey/proposals"))
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody(request, payload)));
        var headers = headersFactory.headersFor(InternalAiTokenRequest.forJourneyProposal(
                request.requestId(),
                request.traceId(),
                request.runId().toString(),
                request.datasetRevision(),
                request.deadlineAt()));
        headers.forEach(builder::header);
        return builder.build();
    }

    private String requestBody(JourneyWorkerRequest request, CandidatePayload payload)
            throws JsonProcessingException {
        return objectMapper.writeValueAsString(Map.of(
                "schemaVersion", "internal.ai.v1",
                "requestId", request.requestId(),
                "runId", request.runId().toString(),
                "candidateCount", payload.candidates().size()));
    }

    private JourneyWorkerPlan planFrom(JourneyWorkerRequest request, String body) {
        try {
            JsonNode root = objectMapper.readTree(body);
            if (!request.runId().toString().equals(text(root, "runId"))) {
                throw degraded(request, WorkerDegradedReason.AI_INVALID_RESPONSE, 200);
            }
            var outcome = text(root, "outcome");
            var refs = root.get("orderedRefs");
            if (outcome == null || outcome.isBlank() || refs == null || !refs.isArray()) {
                throw degraded(request, WorkerDegradedReason.AI_INVALID_RESPONSE, 200);
            }
            List<String> orderedRefs = new ArrayList<>();
            for (JsonNode ref : refs) {
                if (!ref.isTextual() || ref.textValue().isBlank()) {
                    throw degraded(request, WorkerDegradedReason.AI_INVALID_RESPONSE, 200);
                }
                orderedRefs.add(ref.textValue());
            }
            return new JourneyWorkerPlan(orderedRefs, outcome);
        } catch (JsonProcessingException exception) {
            throw degraded(request, WorkerDegradedReason.AI_INVALID_RESPONSE, 200);
        }
    }

    private static String text(JsonNode root, String field) {
        var node = root.get(field);
        return node == null || !node.isTextual() ? null : node.textValue();
    }

    private AiProposalException degraded(
            JourneyWorkerRequest request,
            WorkerDegradedReason reason,
            Integer status) {
        record(request, "FAILURE", status, reason);
        return new AiProposalException(reason);
    }

    private void record(
            JourneyWorkerRequest request,
            String status,
            Integer httpStatus,
            WorkerDegradedReason reason) {
        Map<String, String> attributes = new LinkedHashMap<>();
        attributes.put("run.id", request.runId().toString());
        attributes.put("ai.call.status", status);
        if (httpStatus != null) {
            attributes.put("http.status", Integer.toString(httpStatus));
        }
        if (reason != null) {
            attributes.put("degraded.reason", reason.name());
        }
        telemetry.record(new WorkerTelemetryEvent("journey.worker.ai_call", attributes));
    }
}
