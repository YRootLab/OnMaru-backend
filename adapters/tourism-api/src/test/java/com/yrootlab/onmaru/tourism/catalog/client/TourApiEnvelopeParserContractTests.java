package com.yrootlab.onmaru.tourism.catalog.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.net.http.HttpHeaders;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TourApiEnvelopeParserContractTests {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final TourApiEnvelopeParser parser = new TourApiEnvelopeParser(objectMapper);

    @ParameterizedTest
    @CsvSource({
            "normal-list.json, SUCCESS, 3, false",
            "location-list.json, SUCCESS, 2, true",
            "detail-common.json, SUCCESS, 1, true",
            "last-page.json, SUCCESS, 2, true",
            "empty-list.json, SUCCESS, 0, true"
    })
    void parsesQualificationSuccessFixturesIntoTypedPages(
            String fixtureName,
            TourApiOutcomeKind expectedKind,
            int expectedItemCount,
            boolean expectedLastPage
    ) throws IOException {
        TourApiFixture fixture = readFixture(fixtureName);
        TourApiResponse response = fixture.toResponse();

        TourApiParseResult result = parser.parse(response);

        assertThat(result.kind()).isEqualTo(expectedKind);
        assertThat(result.error()).isNull();
        assertThat(result.page()).isNotNull();
        assertThat(result.page().items()).hasSize(expectedItemCount);
        assertThat(result.page().isLastPage()).isEqualTo(expectedLastPage);
        assertThat(result.page().operation()).isEqualTo(fixture.operation());
    }

    @ParameterizedTest
    @CsvSource({
            "http-200-error-envelope.json, PROVIDER_PARAMETER_ERROR, false",
            "http-4xx.json, AUTH_OR_PERMISSION_ERROR, false",
            "http-5xx.json, UPSTREAM_TIMEOUT_OR_GATEWAY_ERROR, true",
            "rate-limited-429.json, RATE_LIMITED, true"
    })
    void classifiesProviderFailuresWithoutCountingThemAsSuccess(
            String fixtureName,
            TourApiOutcomeKind expectedKind,
            boolean expectedRetryable
    ) throws IOException {
        TourApiFixture fixture = readFixture(fixtureName);
        TourApiResponse response = fixture.toResponse();

        TourApiParseResult result = parser.parse(response);

        assertThat(result.kind()).isEqualTo(expectedKind);
        assertThat(result.page()).isNull();
        assertThat(result.error()).isNotNull();
        assertThat(result.error().retryable()).isEqualTo(expectedRetryable);
        assertThat(result.error().operation()).isEqualTo(fixture.operation());
    }

    @Test
    void preservesRetryAfterForRateLimitedResponses() throws IOException {
        TourApiFixture fixture = readFixture("rate-limited-429.json");

        TourApiParseResult result = parser.parse(fixture.toResponse());

        assertThat(result.kind()).isEqualTo(TourApiOutcomeKind.RATE_LIMITED);
        assertThat(result.error().retryAfterSeconds()).isEqualTo(60);
    }

    @Test
    void rejectsUnexpectedContentTypeAsSchemaDrift() throws IOException {
        TourApiFixture fixture = readFixture("normal-list.json");
        TourApiResponse response = fixture.toResponseWithContentType("text/html");

        TourApiParseResult result = parser.parse(response);

        assertThat(result.kind()).isEqualTo(TourApiOutcomeKind.SCHEMA_DRIFT);
        assertThat(result.error().retryable()).isFalse();
    }

    @Test
    void classifiesGatewayErrorByStatusBeforeContentType() throws IOException {
        TourApiFixture fixture = readFixture("http-5xx.json");
        TourApiResponse response = fixture.toResponseWithContentType("text/html");

        TourApiParseResult result = parser.parse(response);

        assertThat(result.kind()).isEqualTo(TourApiOutcomeKind.UPSTREAM_TIMEOUT_OR_GATEWAY_ERROR);
        assertThat(result.error().retryable()).isTrue();
    }

    @Test
    void classifiesRateLimitByStatusBeforeContentType() throws IOException {
        TourApiFixture fixture = readFixture("rate-limited-429.json");
        TourApiResponse response = fixture.toResponseWithContentType("text/html");

        TourApiParseResult result = parser.parse(response);

        assertThat(result.kind()).isEqualTo(TourApiOutcomeKind.RATE_LIMITED);
        assertThat(result.error().retryable()).isTrue();
        assertThat(result.error().retryAfterSeconds()).isEqualTo(60);
    }

    @Test
    void rejectsMissingPaginationFieldsAsSchemaDrift() {
        TourApiResponse response = jsonResponse("""
                {
                  "response": {
                    "header": {
                      "resultCode": "0000",
                      "resultMsg": "OK"
                    },
                    "body": {
                      "items": {
                        "item": []
                      },
                      "pageNo": 1,
                      "totalCount": 1
                    }
                  }
                }
                """);

        TourApiParseResult result = parser.parse(response);

        assertThat(result.kind()).isEqualTo(TourApiOutcomeKind.SCHEMA_DRIFT);
        assertThat(result.error().retryable()).isFalse();
    }

    @Test
    void rejectsZeroNumOfRowsAsSchemaDrift() {
        TourApiResponse response = jsonResponse("""
                {
                  "response": {
                    "header": {
                      "resultCode": "0000",
                      "resultMsg": "OK"
                    },
                    "body": {
                      "items": {
                        "item": []
                      },
                      "numOfRows": 0,
                      "pageNo": 1,
                      "totalCount": 1
                    }
                  }
                }
                """);

        TourApiParseResult result = parser.parse(response);

        assertThat(result.kind()).isEqualTo(TourApiOutcomeKind.SCHEMA_DRIFT);
        assertThat(result.error().retryable()).isFalse();
    }

    @Test
    void parsesSingletonItemAsOneTypedSourceRecord() {
        TourApiResponse response = jsonResponse("""
                {
                  "response": {
                    "header": {
                      "resultCode": "0000",
                      "resultMsg": "OK"
                    },
                    "body": {
                      "items": {
                        "item": {
                          "contentid": "126508",
                          "title": "경복궁"
                        }
                      },
                      "numOfRows": 10,
                      "pageNo": 1,
                      "totalCount": 1
                    }
                  }
                }
                """);

        TourApiParseResult result = parser.parse(response);

        assertThat(result.kind()).isEqualTo(TourApiOutcomeKind.SUCCESS);
        assertThat(result.page().items()).hasSize(1);
        assertThat(result.page().items().getFirst().fields().path("contentid").asText()).isEqualTo("126508");
        assertThat(result.page().isLastPage()).isTrue();
    }

    @Test
    void classifiesXmlServiceEnvelopeFailures() {
        TourApiResponse response = new TourApiResponse(
                "areaBasedList2",
                503,
                HttpHeaders.of(Map.of("Content-Type", List.of("application/xml")), (name, value) -> true),
                """
                        <OpenAPI_ServiceResponse>
                          <cmmMsgHeader>
                            <errMsg>SERVICETIMEOUT_ERROR</errMsg>
                            <returnAuthMsg>기관 API 또는 GW 연계 서비스 응답 대기시간 초과</returnAuthMsg>
                            <returnReasonCode>05</returnReasonCode>
                          </cmmMsgHeader>
                        </OpenAPI_ServiceResponse>
                        """
        );

        TourApiParseResult result = parser.parse(response);

        assertThat(result.kind()).isEqualTo(TourApiOutcomeKind.UPSTREAM_TIMEOUT_OR_GATEWAY_ERROR);
        assertThat(result.error().retryable()).isTrue();
        assertThat(result.error().providerCode()).isEqualTo("05");
    }

    @Test
    void classifiesHttpOkServiceEnvelopeQuotaFailures() {
        TourApiResponse response = jsonResponse("""
                {
                  "OpenAPI_ServiceResponse": {
                    "cmmMsgHeader": {
                      "errMsg": "LIMITED_NUMBER_OF_SERVICE_REQUESTS_PER_DAY_EXCEEDS_ERROR",
                      "returnAuthMsg": "일일 호출 허용량 초과",
                      "returnReasonCode": "22"
                    }
                  }
                }
                """);

        TourApiParseResult result = parser.parse(response);

        assertThat(result.kind()).isEqualTo(TourApiOutcomeKind.RATE_LIMITED);
        assertThat(result.page()).isNull();
        assertThat(result.error().retryable()).isTrue();
        assertThat(result.error().providerCode()).isEqualTo("22");
    }

    @Test
    void exposesIssueDefaultTimeoutAndRetryBudget() {
        TourApiClientProperties defaults = TourApiClientProperties.defaults();

        assertThat(defaults.connectTimeout()).isEqualTo(Duration.ofSeconds(1));
        assertThat(defaults.attemptTimeout()).isEqualTo(Duration.ofSeconds(5));
        assertThat(defaults.pageTimeout()).isEqualTo(Duration.ofSeconds(12));
        assertThat(defaults.retryCount()).isEqualTo(2);
    }

    private TourApiFixture readFixture(String fixtureName) throws IOException {
        try (InputStream stream = getClass().getResourceAsStream("/tourapi/" + fixtureName)) {
            assertThat(stream).as("fixture exists: %s", fixtureName).isNotNull();
            JsonNode root = objectMapper.readTree(stream);
            return new TourApiFixture(
                    root.path("operation").asText(),
                    root.path("observed").path("httpStatus").asInt(),
                    root.path("observed").path("contentType").asText(),
                    root.path("observed").path("retryAfterSeconds").isMissingNode()
                            ? null
                            : root.path("observed").path("retryAfterSeconds").asLong(),
                    root.path("body")
            );
        }
    }

    private TourApiResponse jsonResponse(String body) {
        return new TourApiResponse(
                "areaBasedList2",
                200,
                HttpHeaders.of(Map.of("Content-Type", List.of("application/json")), (name, value) -> true),
                body
        );
    }

    private record TourApiFixture(
            String operation,
            int httpStatus,
            String contentType,
            Long retryAfterSeconds,
            JsonNode body
    ) {
        TourApiResponse toResponse() {
            return toResponseWithContentType(contentType);
        }

        TourApiResponse toResponseWithContentType(String overrideContentType) {
            Map<String, List<String>> headers = retryAfterSeconds == null
                    ? Map.of("Content-Type", List.of(overrideContentType))
                    : Map.of(
                    "Content-Type", List.of(overrideContentType),
                    "Retry-After", List.of(Long.toString(retryAfterSeconds))
            );
            return new TourApiResponse(
                    operation,
                    httpStatus,
                    HttpHeaders.of(headers, (name, value) -> true),
                    body.toString()
            );
        }
    }
}
