package com.yrootlab.onmaru.tourism.insights;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

final class JdkDataLabHttpTransport implements DataLabHttpTransport {

    private final HttpClient client;

    JdkDataLabHttpTransport(Duration connectTimeout) {
        this.client = HttpClient.newBuilder().connectTimeout(connectTimeout).build();
    }

    @Override
    public DataLabHttpResponse get(URI uri, Duration timeout) throws IOException, InterruptedException {
        var request = HttpRequest.newBuilder(uri)
                .timeout(timeout)
                .header("Accept", "application/json")
                .GET()
                .build();
        var response = client.send(request, HttpResponse.BodyHandlers.ofString());
        Long retryAfter = response.headers().firstValue("Retry-After")
                .flatMap(value -> {
                    try {
                        return java.util.Optional.of(Long.parseLong(value));
                    } catch (NumberFormatException ignored) {
                        return java.util.Optional.empty();
                    }
                }).orElse(null);
        return new DataLabHttpResponse(response.statusCode(), response.body(), retryAfter);
    }
}
