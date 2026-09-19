package com.yrootlab.onmaru.tourism.audio.client;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

public final class OdiiHttpClient {

    private final HttpClient httpClient;
    private final OdiiClientProperties properties;
    private final OdiiEnvelopeParser parser;
    private final OdiiRetrySleeper sleeper;

    public OdiiHttpClient(ObjectMapper objectMapper, OdiiClientProperties properties) {
        this(objectMapper, properties, OdiiRetrySleeper.threadSleep());
    }

    public OdiiHttpClient(
            ObjectMapper objectMapper,
            OdiiClientProperties properties,
            OdiiRetrySleeper sleeper
    ) {
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
        this.parser = new OdiiEnvelopeParser(Objects.requireNonNull(objectMapper, "objectMapper must not be null"));
        this.sleeper = Objects.requireNonNull(sleeper, "sleeper must not be null");
        this.httpClient = HttpClient.newBuilder().connectTimeout(properties.connectTimeout()).build();
    }

    public OdiiPage get(String operation, URI uri) throws InterruptedException {
        Instant deadline = Instant.now().plus(properties.pageTimeout());
        OdiiClientException lastFailure = null;
        int maxAttempts = properties.retryCount() + 1;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            Duration remaining = Duration.between(Instant.now(), deadline);
            if (!remaining.isPositive()) {
                break;
            }
            HttpRequest request = HttpRequest.newBuilder(uri)
                    .timeout(shorterOf(properties.attemptTimeout(), remaining))
                    .header("Accept", "application/json")
                    .GET()
                    .build();
            try {
                HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
                if (response.statusCode() >= 200 && response.statusCode() < 300) {
                    try {
                        return parser.parse(operation, response.body());
                    } catch (OdiiParseException exception) {
                        lastFailure = new OdiiClientException(
                                exception.getMessage(), exception.retryable(), exception);
                        if (exception.retryable() && attempt < maxAttempts) {
                            continue;
                        }
                        throw lastFailure;
                    }
                }
                boolean retryable = response.statusCode() == 429 || response.statusCode() >= 500;
                lastFailure = new OdiiClientException("ODII_HTTP_ERROR: " + response.statusCode(), retryable);
                if (retryable && attempt < maxAttempts) {
                    if (respectRetryAfter(response, deadline)) {
                        continue;
                    }
                }
                throw lastFailure;
            } catch (HttpTimeoutException exception) {
                lastFailure = new OdiiClientException("ODII_TIMEOUT", true, exception);
            } catch (IOException exception) {
                lastFailure = new OdiiClientException("ODII_TRANSPORT_ERROR", true, exception);
            }
            if (attempt == maxAttempts) {
                break;
            }
        }
        throw lastFailure == null
                ? new OdiiClientException("ODII_PAGE_BUDGET_EXHAUSTED", true)
                : lastFailure;
    }

    private boolean respectRetryAfter(HttpResponse<?> response, Instant deadline) throws InterruptedException {
        long seconds = response.headers().firstValue("Retry-After")
                .flatMap(value -> {
                    try {
                        return java.util.Optional.of(Long.parseLong(value));
                    } catch (NumberFormatException exception) {
                        return java.util.Optional.empty();
                    }
                })
                .orElse(0L);
        if (seconds <= 0) {
            return true;
        }
        if (!Instant.now().plusSeconds(seconds).isBefore(deadline)) {
            return false;
        }
        sleeper.sleep(seconds);
        return true;
    }

    private Duration shorterOf(Duration first, Duration second) {
        return first.compareTo(second) <= 0 ? first : second;
    }
}
