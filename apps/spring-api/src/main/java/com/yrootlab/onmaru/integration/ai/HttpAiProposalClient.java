package com.yrootlab.onmaru.integration.ai;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yrootlab.onmaru.integration.ai.security.InternalAiRequestHeadersFactory;
import com.yrootlab.onmaru.integration.ai.security.InternalAiTokenRequest;
import com.yrootlab.onmaru.journey.worker.AiProposalClient;
import com.yrootlab.onmaru.journey.worker.AiProposalException;
import com.yrootlab.onmaru.journey.worker.CandidatePayload;
import com.yrootlab.onmaru.journey.worker.JourneyCandidate;
import com.yrootlab.onmaru.journey.worker.JourneyWorkerPlan;
import com.yrootlab.onmaru.journey.worker.JourneyWorkerRequest;
import com.yrootlab.onmaru.journey.worker.JourneyWorkerTelemetry;
import com.yrootlab.onmaru.journey.worker.WorkerDegradedReason;
import com.yrootlab.onmaru.journey.worker.WorkerTelemetryEvent;

import java.io.IOException;
import java.io.InputStream;
import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.nio.charset.StandardCharsets;
import java.nio.charset.CodingErrorAction;
import java.nio.ByteBuffer;
import java.util.concurrent.FutureTask;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

public final class HttpAiProposalClient implements AiProposalClient {

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final URI baseUri;
    private final InternalAiRequestHeadersFactory headersFactory;
    private final Duration timeout;
    private final JourneyWorkerTelemetry telemetry;
    private final boolean streamingEnabled;
    private final boolean unaryFallbackEnabled;

    public HttpAiProposalClient(
            HttpClient httpClient,
            ObjectMapper objectMapper,
            URI baseUri,
            InternalAiRequestHeadersFactory headersFactory,
            Duration timeout,
            JourneyWorkerTelemetry telemetry) {
        this(httpClient, objectMapper, baseUri, headersFactory, timeout, telemetry, true, true);
    }

    public HttpAiProposalClient(HttpClient httpClient, ObjectMapper objectMapper, URI baseUri,
            InternalAiRequestHeadersFactory headersFactory, Duration timeout, JourneyWorkerTelemetry telemetry,
            boolean streamingEnabled, boolean unaryFallbackEnabled) {
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
        this.baseUri = baseUri;
        this.headersFactory = headersFactory;
        this.timeout = timeout;
        this.telemetry = telemetry;
        this.streamingEnabled = streamingEnabled;
        this.unaryFallbackEnabled = unaryFallbackEnabled;
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
            var plan = planFrom(request, payload, response.body());
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

    @Override
    public JourneyWorkerPlan propose(JourneyWorkerRequest request, CandidatePayload payload, Consumer<String> textDelta) {
        if (!streamingEnabled) {
            return propose(request, payload);
        }
        var body = new AtomicReference<InputStream>();
        var task = new FutureTask<JourneyWorkerPlan>(() -> stream(request, payload, textDelta, body));
        Thread.startVirtualThread(task);
        try {
            return task.get(Math.max(1, timeout.toMillis()), TimeUnit.MILLISECONDS);
        } catch (TimeoutException exception) {
            throw degraded(request, WorkerDegradedReason.AI_TIMEOUT, null);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw degraded(request, WorkerDegradedReason.AI_SERVICE_UNAVAILABLE, null);
        } catch (ExecutionException exception) {
            if (exception.getCause() instanceof AiProposalException failure) throw failure;
            throw degraded(request, WorkerDegradedReason.AI_SERVICE_UNAVAILABLE, null);
        } finally {
            if (!task.isDone()) task.cancel(true);
            var stream = body.get();
            if (stream != null) {
                try { stream.close(); } catch (IOException ignored) { /* already failed/closed */ }
            }
        }
    }

    private JourneyWorkerPlan stream(JourneyWorkerRequest request, CandidatePayload payload,
            Consumer<String> textDelta, AtomicReference<InputStream> body) {
        try {
            var response = httpClient.send(httpRequest(request, payload, true), HttpResponse.BodyHandlers.ofInputStream());
            body.set(response.body());
            try (var input = response.body()) {
                if (unaryFallbackEnabled && (List.of(404, 405, 501, 503).contains(response.statusCode())
                        || (response.statusCode() >= 200 && response.statusCode() < 300
                            && !response.headers().firstValue("Content-Type").orElse("").toLowerCase(java.util.Locale.ROOT).startsWith("text/event-stream")))) {
                    input.close();
                    return propose(request, payload);
                }
                if (response.statusCode() == 429) throw degraded(request, WorkerDegradedReason.AI_QUOTA_EXCEEDED, 429);
                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    throw degraded(request, WorkerDegradedReason.AI_SERVICE_UNAVAILABLE, response.statusCode());
                }
                if (!response.headers().firstValue("Content-Type").orElse("").toLowerCase(java.util.Locale.ROOT).startsWith("text/event-stream")) {
                    throw degraded(request, WorkerDegradedReason.AI_INVALID_RESPONSE, response.statusCode());
                }
                var plan = readStream(request, payload, input, textDelta);
                record(request, "SUCCESS", response.statusCode(), null);
                return plan;
            }
        } catch (java.net.http.HttpTimeoutException exception) {
            throw degraded(request, WorkerDegradedReason.AI_TIMEOUT, null);
        } catch (IOException exception) {
            throw degraded(request, WorkerDegradedReason.AI_SERVICE_UNAVAILABLE, null);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw degraded(request, WorkerDegradedReason.AI_SERVICE_UNAVAILABLE, null);
        }
    }

    private JourneyWorkerPlan readStream(JourneyWorkerRequest request, CandidatePayload payload,
            InputStream raw, Consumer<String> textDelta) throws IOException {
        var input = new BufferedInputStream(raw, 4096);
        var lineBytes = new ByteArrayOutputStream();
        var data = new StringBuilder();
        String event = null;
        JourneyWorkerPlan proposal = null;
        int total = 0;
        int frame = 0;
        int publicCharacters = 0;
        int value;
        while ((value = input.read()) != -1) {
            if (Thread.currentThread().isInterrupted()) throw new IOException();
            if (++total > 1_048_576 || ++frame > 65_536 || lineBytes.size() >= 65_536) {
                throw degraded(request, WorkerDegradedReason.AI_INVALID_RESPONSE, 200);
            }
            if (value != '\n') {
                lineBytes.write(value);
                continue;
            }
            String line;
            try {
                line = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(lineBytes.toByteArray())).toString();
            } catch (java.nio.charset.CharacterCodingException exception) {
                throw degraded(request, WorkerDegradedReason.AI_INVALID_RESPONSE, 200);
            }
            lineBytes.reset();
            if (line.endsWith("\r")) line = line.substring(0, line.length() - 1);
            if (line.isEmpty()) {
                frame = 0;
                if (event != null || !data.isEmpty()) {
                    if (proposal != null || event == null || data.isEmpty()) throw degraded(request, WorkerDegradedReason.AI_INVALID_RESPONSE, 200);
                    var root = streamJson(request, data.toString());
                    switch (event) {
                        case "text.delta" -> {
                            var text = text(root, "text");
                            if (root.size() != 1 || text == null || text.isEmpty()
                                    || text.codePointCount(0, text.length()) > 512) throw degraded(request, WorkerDegradedReason.AI_INVALID_RESPONSE, 200);
                            publicCharacters += text.codePointCount(0, text.length());
                            if (publicCharacters > 4000) throw degraded(request, WorkerDegradedReason.AI_INVALID_RESPONSE, 200);
                            textDelta.accept(text);
                        }
                        case "proposal" -> {
                            if (root.size() != 1 || !root.has("proposal") || !root.get("proposal").isObject()) throw degraded(request, WorkerDegradedReason.AI_INVALID_RESPONSE, 200);
                            proposal = planFrom(request, payload, root.get("proposal"));
                        }
                        case "error" -> {
                            if (root.size() != 1) throw degraded(request, WorkerDegradedReason.AI_INVALID_RESPONSE, 200);
                            WorkerDegradedReason reason;
                            try { reason = WorkerDegradedReason.valueOf(text(root, "code")); }
                            catch (IllegalArgumentException | NullPointerException exception) { reason = WorkerDegradedReason.AI_INVALID_RESPONSE; }
                            throw degraded(request, reason, 200);
                        }
                        default -> throw degraded(request, WorkerDegradedReason.AI_INVALID_RESPONSE, 200);
                    }
                }
                event = null;
                data.setLength(0);
            } else if (line.startsWith("event:")) {
                if (event != null) throw degraded(request, WorkerDegradedReason.AI_INVALID_RESPONSE, 200);
                event = line.substring(6).strip();
            } else if (line.startsWith("data:")) {
                if (!data.isEmpty()) data.append('\n');
                var part = line.substring(5);
                data.append(part.startsWith(" ") ? part.substring(1) : part);
            } else if (!line.startsWith(":") && !line.startsWith("id:") && !line.startsWith("retry:")) {
                throw degraded(request, WorkerDegradedReason.AI_INVALID_RESPONSE, 200);
            }
        }
        if (lineBytes.size() != 0 || event != null || !data.isEmpty() || proposal == null) {
            throw degraded(request, WorkerDegradedReason.AI_INVALID_RESPONSE, 200);
        }
        return proposal;
    }

    private JsonNode streamJson(JourneyWorkerRequest request, String data) {
        try (var parser = objectMapper.getFactory().createParser(data)) {
            parser.enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
            JsonNode root = objectMapper.readTree(parser);
            if (root == null || !root.isObject() || parser.nextToken() != null) throw degraded(request, WorkerDegradedReason.AI_INVALID_RESPONSE, 200);
            return root;
        } catch (IOException exception) {
            throw degraded(request, WorkerDegradedReason.AI_INVALID_RESPONSE, 200);
        }
    }

    private HttpRequest httpRequest(JourneyWorkerRequest request, CandidatePayload payload)
            throws JsonProcessingException {
        return httpRequest(request, payload, false);
    }

    private HttpRequest httpRequest(JourneyWorkerRequest request, CandidatePayload payload, boolean stream)
            throws JsonProcessingException {
        var builder = HttpRequest.newBuilder(baseUri.resolve("/internal/v1/journey/proposals" + (stream ? "/stream" : "")))
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody(request, payload)));
        if (stream) builder.header("Accept", "text/event-stream");
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
                "candidateCount", payload.candidates().size(),
                "query", request.queryText(),
                "candidateRefs", payload.candidates().stream().map(JourneyCandidate::ref).toList()));
    }

    private JourneyWorkerPlan planFrom(JourneyWorkerRequest request, CandidatePayload payload, String body) {
        try {
            JsonNode root = objectMapper.readTree(body);
            return planFrom(request, payload, root);
        } catch (JsonProcessingException exception) {
            throw degraded(request, WorkerDegradedReason.AI_INVALID_RESPONSE, 200);
        }
    }

    private JourneyWorkerPlan planFrom(JourneyWorkerRequest request, CandidatePayload payload, JsonNode root) {
            if (root == null || !root.isObject()) throw degraded(request, WorkerDegradedReason.AI_INVALID_RESPONSE, 200);
            if (!request.runId().toString().equals(text(root, "runId"))) {
                throw degraded(request, WorkerDegradedReason.AI_INVALID_RESPONSE, 200);
            }
            var outcome = text(root, "outcome");
            var refs = root.get("orderedRefs");
            if (outcome == null || outcome.isBlank() || refs == null || !refs.isArray()) {
                throw degraded(request, WorkerDegradedReason.AI_INVALID_RESPONSE, 200);
            }
            List<String> orderedRefs = new ArrayList<>();
            var allowed = payload.candidates().stream().map(JourneyCandidate::ref).collect(java.util.stream.Collectors.toSet());
            for (JsonNode ref : refs) {
                if (!ref.isTextual() || ref.textValue().isBlank() || !allowed.contains(ref.textValue()) || orderedRefs.contains(ref.textValue()) || orderedRefs.size() >= 12) {
                    throw degraded(request, WorkerDegradedReason.AI_INVALID_RESPONSE, 200);
                }
                orderedRefs.add(ref.textValue());
            }
            return new JourneyWorkerPlan(orderedRefs, outcome);
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
