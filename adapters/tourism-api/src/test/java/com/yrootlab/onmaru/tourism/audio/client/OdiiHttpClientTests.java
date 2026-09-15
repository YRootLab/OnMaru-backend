package com.yrootlab.onmaru.tourism.audio.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.yrootlab.onmaru.tourism.audio.mapping.OdiiSourceItemMapper;
import com.yrootlab.onmaru.tourism.audio.sync.OdiiStorySearchPageSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OdiiHttpClientTests {

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void retriesServerFailureAndReturnsParsedPage() throws Exception {
        var attempts = new AtomicInteger();
        server = startServer(exchange -> {
            if (attempts.incrementAndGet() == 1) {
                send(exchange, 503, "{}");
            } else {
                send(exchange, 200, successBody());
            }
        });

        OdiiPage page = client(1).get("storySearchList", serverUri());

        assertThat(page.items()).hasSize(1);
        assertThat(attempts).hasValue(2);
    }

    @Test
    void doesNotRetryProviderAuthenticationError() throws Exception {
        var attempts = new AtomicInteger();
        server = startServer(exchange -> {
            attempts.incrementAndGet();
            send(exchange, 200, """
                    {"OpenAPI_ServiceResponse":{"cmmMsgHeader":{"returnReasonCode":"20"}}}
                    """);
        });

        assertThatThrownBy(() -> client(2).get("storySearchList", serverUri()))
                .isInstanceOf(OdiiClientException.class)
                .satisfies(error -> assertThat(((OdiiClientException) error).retryable()).isFalse());
        assertThat(attempts).hasValue(1);
    }

    @Test
    void retriesRateLimitReportedInsideHttpSuccessEnvelope() throws Exception {
        var attempts = new AtomicInteger();
        server = startServer(exchange -> {
            if (attempts.incrementAndGet() == 1) {
                send(exchange, 200, """
                        {"OpenAPI_ServiceResponse":{"cmmMsgHeader":{"returnReasonCode":"23"}}}
                        """);
            } else {
                send(exchange, 200, successBody());
            }
        });

        OdiiPage page = client(1).get("storySearchList", serverUri());

        assertThat(page.items()).hasSize(1);
        assertThat(attempts).hasValue(2);
    }

    @Test
    void buildsEncodedStoryUriAndRedactsServiceKey() {
        var builder = new OdiiUriBuilder(
                URI.create("https://apis.data.go.kr/B551011/Odii"),
                "secret+/=",
                "OnMaru"
        );

        URI uri = builder.storyBased("ko", "30", "102", 2, 20);

        assertThat(uri.getRawQuery())
                .contains("langCode=ko", "tid=30", "tlid=102", "pageNo=2", "numOfRows=20")
                .contains("serviceKey=secret%2B%2F%3D");
        assertThat(OdiiUriBuilder.redactServiceKey(uri))
                .doesNotContain("secret", "%2B%2F%3D")
                .contains("serviceKey=%3CREDACTED%3E");

        URI encodedKeyUri = new OdiiUriBuilder(
                URI.create("https://apis.data.go.kr/B551011/Odii"),
                "already%2Bencoded",
                "OnMaru"
        ).storyBased("ko", "30", "102", 1, 20);
        assertThat(encodedKeyUri.getRawQuery())
                .contains("serviceKey=already%2Bencoded")
                .doesNotContain("already%252Bencoded");
    }

    @Test
    void doesNotRetryRateLimitWhenRetryAfterExceedsPageBudget() throws Exception {
        var attempts = new AtomicInteger();
        server = startServer(exchange -> {
            attempts.incrementAndGet();
            exchange.getResponseHeaders().add("Retry-After", "60");
            send(exchange, 429, "{}");
        });
        var client = new OdiiHttpClient(
                new ObjectMapper(),
                new OdiiClientProperties(
                        Duration.ofSeconds(1),
                        Duration.ofSeconds(1),
                        Duration.ofSeconds(2),
                        2
                ),
                ignored -> { }
        );

        assertThatThrownBy(() -> client.get("storySearchList", serverUri()))
                .isInstanceOf(OdiiClientException.class)
                .satisfies(error -> assertThat(((OdiiClientException) error).retryable()).isTrue());
        assertThat(attempts).hasValue(1);
    }

    @Test
    void storySearchPageSourceConnectsHttpQueryToAudioOwnedPort() throws Exception {
        var requestedQuery = new AtomicReference<String>();
        server = startServer(exchange -> {
            requestedQuery.set(exchange.getRequestURI().getRawQuery());
            send(exchange, 200, successBody());
        });
        var source = new OdiiStorySearchPageSource(
                client(0),
                new OdiiUriBuilder(serverUri(), "secret", "OnMaru"),
                new OdiiSourceItemMapper(),
                "한옥",
                20
        );

        var page = source.fetch("ko", 1);

        assertThat(page.lastPage()).isTrue();
        assertThat(page.stories()).singleElement().satisfies(story -> {
            assertThat(story.stid()).isEqualTo("562");
            assertThat(story.langCode()).isEqualTo("ko");
        });
        assertThat(requestedQuery.get())
                .contains("langCode=ko", "pageNo=1", "numOfRows=20", "keyword=%ED%95%9C%EC%98%A5");
    }

    private OdiiHttpClient client(int retries) {
        return new OdiiHttpClient(
                new ObjectMapper(),
                new OdiiClientProperties(
                        Duration.ofSeconds(1),
                        Duration.ofSeconds(2),
                        Duration.ofSeconds(5),
                        retries
                ),
                ignored -> { }
        );
    }

    private HttpServer startServer(ExchangeHandler handler) throws IOException {
        HttpServer httpServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        httpServer.createContext("/odii", exchange -> {
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
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/odii");
    }

    private void send(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    private String successBody() {
        return """
                {
                  "response": {
                    "header": {"resultCode": "0000", "resultMsg": "OK"},
                    "body": {
                      "items": {"item": {
                        "tid": "89", "tlid": "300", "stid": "562", "stlid": "1204",
                        "title": "남산골 한옥마을", "audioTitle": "개요", "script": "대본",
                        "audioUrl": "", "imageUrl": "", "playTime": "105",
                        "mapX": "126.99", "mapY": "37.55", "langCode": "ko",
                        "createdtime": "20150619173503", "modifiedtime": "20250609074606"
                      }},
                      "numOfRows": 1, "pageNo": 1, "totalCount": 1
                    }
                  }
                }
                """;
    }

    private interface ExchangeHandler {
        void handle(HttpExchange exchange) throws IOException;
    }
}
