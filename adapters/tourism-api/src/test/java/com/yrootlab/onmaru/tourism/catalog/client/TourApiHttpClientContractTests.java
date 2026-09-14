package com.yrootlab.onmaru.tourism.catalog.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TourApiHttpClientContractTests {

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void retriesRetryableFailuresWithinBudgetAndReturnsTypedSuccess() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        server = startServer(exchange -> {
            int attempt = attempts.incrementAndGet();
            if (attempt == 1) {
                send(exchange, 503, "application/json", """
                        {
                          "OpenAPI_ServiceResponse": {
                            "cmmMsgHeader": {
                              "errMsg": "SERVICETIMEOUT_ERROR",
                              "returnAuthMsg": "기관 API 또는 GW 연계 서비스 응답 대기시간 초과",
                              "returnReasonCode": "05"
                            }
                          }
                        }
                        """);
                return;
            }
            send(exchange, 200, "application/json", successBody());
        });
        TourApiHttpClient client = clientWithRetryCount(2, ignored -> { });

        TourApiParseResult result = client.get("areaBasedList2", serverUri());

        assertThat(result.kind()).isEqualTo(TourApiOutcomeKind.SUCCESS);
        assertThat(result.page().items()).hasSize(1);
        assertThat(attempts).hasValue(2);
    }

    @Test
    void doesNotRetryNonRetryableAuthenticationFailures() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        server = startServer(exchange -> {
            attempts.incrementAndGet();
            send(exchange, 401, "application/json", """
                    {
                      "OpenAPI_ServiceResponse": {
                        "cmmMsgHeader": {
                          "errMsg": "SERVICE_KEY_IS_NULL",
                          "returnAuthMsg": "서비스 접근거부",
                          "returnReasonCode": "20"
                        }
                      }
                    }
                    """);
        });
        TourApiHttpClient client = clientWithRetryCount(2, ignored -> { });

        TourApiParseResult result = client.get("areaCode2", serverUri());

        assertThat(result.kind()).isEqualTo(TourApiOutcomeKind.AUTH_OR_PERMISSION_ERROR);
        assertThat(attempts).hasValue(1);
    }

    @Test
    void honorsRetryAfterBeforeRetryingRateLimitedResponses() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        AtomicLong sleptSeconds = new AtomicLong();
        server = startServer(exchange -> {
            int attempt = attempts.incrementAndGet();
            if (attempt == 1) {
                exchange.getResponseHeaders().add("Retry-After", "60");
                send(exchange, 429, "application/json", """
                        {
                          "OpenAPI_ServiceResponse": {
                            "cmmMsgHeader": {
                              "errMsg": "LIMITED_NUMBER_OF_SERVICE_REQUESTS_PER_SECOND_EXCEEDS_ERROR",
                              "returnAuthMsg": "초당 호출 허용량 초과",
                              "returnReasonCode": "23"
                            }
                          }
                        }
                        """);
                return;
            }
            send(exchange, 200, "application/json", successBody());
        });
        TourApiHttpClient client = clientWithRetryCount(2, Duration.ofSeconds(70), sleptSeconds::set);

        TourApiParseResult result = client.get("areaBasedList2", serverUri());

        assertThat(result.kind()).isEqualTo(TourApiOutcomeKind.SUCCESS);
        assertThat(attempts).hasValue(2);
        assertThat(sleptSeconds).hasValue(60);
    }

    @Test
    void returnsRetryableErrorWhenTransportFailsAfterBudget() throws Exception {
        URI unavailableUri = URI.create("http://127.0.0.1:" + unusedPort() + "/tourapi");
        TourApiHttpClient client = clientWithRetryCount(1, ignored -> { });

        TourApiParseResult result = client.get("areaBasedList2", unavailableUri);

        assertThat(result.kind()).isEqualTo(TourApiOutcomeKind.UPSTREAM_TIMEOUT_OR_GATEWAY_ERROR);
        assertThat(result.error().retryable()).isTrue();
    }

    @Test
    void doesNotSleepPastPageTimeoutBudget() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        AtomicLong sleptSeconds = new AtomicLong();
        server = startServer(exchange -> {
            attempts.incrementAndGet();
            exchange.getResponseHeaders().add("Retry-After", "60");
            send(exchange, 429, "application/json", """
                    {
                      "OpenAPI_ServiceResponse": {
                        "cmmMsgHeader": {
                          "errMsg": "LIMITED_NUMBER_OF_SERVICE_REQUESTS_PER_SECOND_EXCEEDS_ERROR",
                          "returnAuthMsg": "초당 호출 허용량 초과",
                          "returnReasonCode": "23"
                        }
                      }
                    }
                    """);
        });
        TourApiHttpClient client = clientWithRetryCount(2, Duration.ofSeconds(1), sleptSeconds::set);

        TourApiParseResult result = client.get("areaBasedList2", serverUri());

        assertThat(result.kind()).isEqualTo(TourApiOutcomeKind.RATE_LIMITED);
        assertThat(attempts).hasValue(1);
        assertThat(sleptSeconds).hasValue(0);
    }

    @Test
    void rejectsZeroPageTimeoutConfiguration() {
        assertThatThrownBy(() -> clientWithRetryCount(2, Duration.ZERO, ignored -> { }))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("pageTimeout");
    }

    private TourApiHttpClient clientWithRetryCount(int retryCount, TourApiRetrySleeper sleeper) {
        return clientWithRetryCount(retryCount, Duration.ofSeconds(12), sleeper);
    }

    private TourApiHttpClient clientWithRetryCount(
            int retryCount,
            Duration pageTimeout,
            TourApiRetrySleeper sleeper
    ) {
        return new TourApiHttpClient(
                new ObjectMapper(),
                new TourApiClientProperties(
                        Duration.ofSeconds(1),
                        Duration.ofSeconds(5),
                        pageTimeout,
                        retryCount
                ),
                sleeper
        );
    }

    private int unusedPort() {
        try (java.net.ServerSocket socket = new java.net.ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private HttpServer startServer(ExchangeHandler handler) throws IOException {
        HttpServer httpServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        httpServer.createContext("/tourapi", exchange -> {
            try {
                handler.handle(exchange);
            } finally {
                exchange.close();
            }
        });
        httpServer.start();
        return httpServer;
    }

    private URI serverUri() {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/tourapi");
    }

    private void send(HttpExchange exchange, int status, String contentType, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", contentType);
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    private String successBody() {
        return """
                {
                  "response": {
                    "header": {
                      "resultCode": "0000",
                      "resultMsg": "OK"
                    },
                    "body": {
                      "items": {
                        "item": [
                          {
                            "contentid": "126508",
                            "title": "경복궁"
                          }
                        ]
                      },
                      "numOfRows": 10,
                      "pageNo": 1,
                      "totalCount": 1
                    }
                  }
                }
                """;
    }

    private interface ExchangeHandler {
        void handle(HttpExchange exchange) throws IOException;
    }
}
