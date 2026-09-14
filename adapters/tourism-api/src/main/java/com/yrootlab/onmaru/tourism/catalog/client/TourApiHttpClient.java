package com.yrootlab.onmaru.tourism.catalog.client;

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

public final class TourApiHttpClient {

    private final HttpClient httpClient;
    private final TourApiClientProperties properties;
    private final TourApiEnvelopeParser parser;
    private final TourApiRetrySleeper sleeper;

    public TourApiHttpClient(ObjectMapper objectMapper, TourApiClientProperties properties) {
        this(objectMapper, properties, TourApiRetrySleeper.threadSleep());
    }

    public TourApiHttpClient(
            ObjectMapper objectMapper,
            TourApiClientProperties properties,
            TourApiRetrySleeper sleeper
    ) {
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
        this.parser = new TourApiEnvelopeParser(Objects.requireNonNull(objectMapper, "objectMapper must not be null"));
        this.sleeper = Objects.requireNonNull(sleeper, "sleeper must not be null");
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(properties.connectTimeout())
                .build();
    }

    public TourApiParseResult get(String operation, URI uri) throws IOException, InterruptedException {
        TourApiParseResult lastResult = null;
        int maxAttempts = properties.retryCount() + 1;
        Instant deadline = Instant.now().plus(properties.pageTimeout());
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            Duration remainingBudget = Duration.between(Instant.now(), deadline);
            if (!remainingBudget.isPositive()) {
                return lastResult == null
                        ? transportError(operation, "TourAPI page timeout budget exhausted")
                        : lastResult;
            }
            HttpRequest request = HttpRequest.newBuilder(uri)
                    .timeout(shorterOf(properties.attemptTimeout(), remainingBudget))
                    .header("Accept", "application/json")
                    .GET()
                    .build();
            try {
                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                lastResult = parser.parse(new TourApiResponse(
                        operation,
                        response.statusCode(),
                        response.headers(),
                        response.body()
                ));
            } catch (HttpTimeoutException exception) {
                lastResult = transportError(operation, "TourAPI request timed out");
            } catch (IOException exception) {
                lastResult = transportError(operation, "TourAPI request failed");
            }

            if (!shouldRetry(lastResult, attempt, maxAttempts)) {
                return lastResult;
            }
            Long retryAfterSeconds = lastResult.error().retryAfterSeconds();
            if (retryAfterSeconds != null && retryAfterSeconds > 0) {
                if (!canSleepWithinDeadline(deadline, retryAfterSeconds)) {
                    return lastResult;
                }
                sleeper.sleep(retryAfterSeconds);
            }
        }
        return lastResult;
    }

    private boolean shouldRetry(TourApiParseResult result, int attempt, int maxAttempts) {
        return result.error() != null
                && result.error().retryable()
                && attempt < maxAttempts;
    }

    private boolean canSleepWithinDeadline(Instant deadline, long retryAfterSeconds) {
        return Instant.now().plusSeconds(retryAfterSeconds).isBefore(deadline);
    }

    private Duration shorterOf(Duration first, Duration second) {
        return first.compareTo(second) <= 0 ? first : second;
    }

    private TourApiParseResult transportError(String operation, String message) {
        return TourApiParseResult.error(new TourApiError(
                operation,
                TourApiOutcomeKind.UPSTREAM_TIMEOUT_OR_GATEWAY_ERROR,
                null,
                message,
                true,
                null
        ));
    }
}
