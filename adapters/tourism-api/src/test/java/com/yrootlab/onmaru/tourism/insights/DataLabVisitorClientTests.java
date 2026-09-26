package com.yrootlab.onmaru.tourism.insights;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayDeque;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DataLabVisitorClientTests {

    @Test
    void collectsAllLocalGovernmentPagesFromTheTopLevelEnvelopeWithoutARegionParameter() throws Exception {
        var transport = new ScriptedTransport(
                response(200, page(1, 2, 3, """
                        [
                          {"baseYmd":"20260924","signguCode":"45111","signguNm":"전주시","touDivCd":"2","touNum":"18240"},
                          {"baseYmd":"20260924","signguCode":"45111","signguNm":"전주시","touDivCd":"3","touNum":"310"}
                        ]
                        """)),
                response(200, page(2, 2, 3, """
                        {"baseYmd":"20260925","signguCode":"45111","signguNm":"전주시","touDivCd":"2","touNum":"19420"}
                        """)));
        var client = client(transport, 0);

        List<DataLabVisitorRecord> records = client.fetchAll(DataLabVisitorRequest.localGovernment(
                LocalDate.parse("2026-09-24"), LocalDate.parse("2026-09-25"), 2));

        assertThat(records).extracting(DataLabVisitorRecord::visitorCount)
                .containsExactly(18240L, 310L, 19420L);
        assertThat(transport.requestUris()).hasSize(2);
        assertThat(transport.requestUris().getFirst().getPath()).isEqualTo("/DataLabService/locgoRegnVisitrDDList");
        assertThat(transport.requestUris().getFirst().getQuery())
                .contains("MobileOS=ETC", "MobileApp=OnMaru", "startYmd=20260924", "endYmd=20260925",
                        "pageNo=1", "numOfRows=2", "_type=json")
                .doesNotContain("signguCd=", "areaCd=");
        assertThat(transport.requestUris().get(1).getQuery()).contains("pageNo=2");
    }

    @Test
    void rejectsProviderErrorEnvelopeWithoutReturningPartialResults() {
        var transport = new ScriptedTransport(response(200, """
                {"response":{"header":{"resultCode":"20","resultMsg":"SERVICE_KEY_IS_NOT_REGISTERED_ERROR"},"body":{}}}
                """));
        var client = client(transport, 2);

        assertThatThrownBy(() -> client.fetchAll(DataLabVisitorRequest.metropolitan(
                LocalDate.parse("2026-09-24"), LocalDate.parse("2026-09-24"), 10)))
                .isInstanceOf(DataLabClientException.class)
                .extracting(exception -> ((DataLabClientException) exception).kind())
                .isEqualTo(DataLabClientFailureKind.AUTH_OR_PERMISSION_ERROR);
        assertThat(transport.requestUris()).hasSize(1);
    }

    @Test
    void retriesRetryableGatewayFailureBeforeReadingThePage() throws Exception {
        var transport = new ScriptedTransport(
                response(503, "{\"message\":\"gateway unavailable\"}"),
                response(200, page(1, 10, 1, """
                        {"baseYmd":"20260924","areaCode":"45","areaNm":"전북특별자치도","touDivCd":"2","touNum":"90000"}
                        """)));
        var client = client(transport, 1);

        var records = client.fetchAll(DataLabVisitorRequest.metropolitan(
                LocalDate.parse("2026-09-24"), LocalDate.parse("2026-09-24"), 10));

        assertThat(records).extracting(DataLabVisitorRecord::visitorCount).containsExactly(90000L);
        assertThat(transport.requestUris()).hasSize(2);
    }

    @Test
    void roundsProviderDecimalVisitorCountsToTheIntegerApiContract() throws Exception {
        var transport = new ScriptedTransport(response(200, page(1, 10, 1, """
                {"baseYmd":"20260924","signguCode":"52113","signguNm":"전주시 덕진구","touDivCd":"2","touNum":"153254.5"}
                """)));
        var client = client(transport, 0);

        var records = client.fetchAll(DataLabVisitorRequest.localGovernment(
                LocalDate.parse("2026-09-24"), LocalDate.parse("2026-09-24"), 10));

        assertThat(records).extracting(DataLabVisitorRecord::visitorCount).containsExactly(153255L);
    }

    private DataLabVisitorClient client(DataLabHttpTransport transport, int retryCount) {
        return new DataLabVisitorClient(
                new ObjectMapper(),
                new DataLabClientProperties(
                        URI.create("https://example.test/DataLabService"), "secret-key", "OnMaru",
                        Duration.ofSeconds(1), Duration.ofSeconds(2), Duration.ofSeconds(10), retryCount),
                transport,
                ignored -> { });
    }

    private static DataLabHttpResponse response(int status, String body) {
        return new DataLabHttpResponse(status, body, null);
    }

    private static String page(int pageNo, int numRows, int total, String items) {
        return """
                {"header":{"resultCode":"0000","resultMsg":"OK"},"body":{
                  "pageNo":%d,"numOfRows":%d,"totalCount":%d,"items":{"item":%s}
                }}
                """.formatted(pageNo, numRows, total, items);
    }

    private static final class ScriptedTransport implements DataLabHttpTransport {
        private final ArrayDeque<DataLabHttpResponse> responses;
        private final java.util.ArrayList<URI> requestUris = new java.util.ArrayList<>();

        private ScriptedTransport(DataLabHttpResponse... responses) {
            this.responses = new ArrayDeque<>(List.of(responses));
        }

        @Override
        public DataLabHttpResponse get(URI uri, Duration timeout) throws IOException {
            requestUris.add(uri);
            if (responses.isEmpty()) {
                throw new IOException("No scripted response");
            }
            return responses.removeFirst();
        }

        private List<URI> requestUris() {
            return List.copyOf(requestUris);
        }
    }
}
