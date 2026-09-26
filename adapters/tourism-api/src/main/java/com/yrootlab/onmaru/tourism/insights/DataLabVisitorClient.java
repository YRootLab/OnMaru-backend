package com.yrootlab.onmaru.tourism.insights;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.StringJoiner;

/**
 * Typed client for DataLab nationwide regional visitor APIs. It retains provider-region
 * codes; Catalog mapping belongs to the ingestion boundary, not this HTTP adapter.
 */
public final class DataLabVisitorClient {

    private static final String SUCCESS_CODE = "0000";
    private static final DateTimeFormatter YMD = DateTimeFormatter.BASIC_ISO_DATE;

    private final ObjectMapper objectMapper;
    private final DataLabClientProperties properties;
    private final DataLabHttpTransport transport;
    private final DataLabRetrySleeper sleeper;

    public DataLabVisitorClient(ObjectMapper objectMapper, DataLabClientProperties properties) {
        this(objectMapper, properties, new JdkDataLabHttpTransport(properties.connectTimeout()), DataLabRetrySleeper.threadSleep());
    }

    public DataLabVisitorClient(
            ObjectMapper objectMapper,
            DataLabClientProperties properties,
            DataLabHttpTransport transport,
            DataLabRetrySleeper sleeper
    ) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
        this.transport = Objects.requireNonNull(transport, "transport must not be null");
        this.sleeper = Objects.requireNonNull(sleeper, "sleeper must not be null");
    }

    public List<DataLabVisitorRecord> fetchAll(DataLabVisitorRequest request) throws InterruptedException {
        Objects.requireNonNull(request, "request must not be null");
        var records = new ArrayList<DataLabVisitorRecord>();
        int requestedPage = 1;
        while (true) {
            DataLabVisitorPage page = fetchPage(request, requestedPage);
            if (page.pageNo() != requestedPage) {
                throw failure(DataLabClientFailureKind.SCHEMA_DRIFT,
                        "DataLab response pageNo does not match requested page", false);
            }
            records.addAll(page.records());
            if (page.isLastPage()) {
                return List.copyOf(records);
            }
            if (page.records().isEmpty()) {
                throw failure(DataLabClientFailureKind.SCHEMA_DRIFT,
                        "DataLab returned an empty non-final page", false);
            }
            requestedPage++;
        }
    }

    private DataLabVisitorPage fetchPage(DataLabVisitorRequest request, int pageNo) throws InterruptedException {
        Instant deadline = Instant.now().plus(properties.pageTimeout());
        DataLabClientException lastFailure = null;
        int maxAttempts = properties.retryCount() + 1;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            Duration remaining = Duration.between(Instant.now(), deadline);
            if (!remaining.isPositive()) {
                break;
            }
            try {
                return parse(request, pageNo, transport.get(uri(request, pageNo), shorterOf(properties.attemptTimeout(), remaining)));
            } catch (DataLabClientException exception) {
                lastFailure = exception;
                if (!exception.retryable() || attempt == maxAttempts || !sleepBeforeRetry(exception, deadline)) {
                    throw exception;
                }
            } catch (IOException exception) {
                lastFailure = new DataLabClientException(DataLabClientFailureKind.UPSTREAM_TIMEOUT_OR_GATEWAY_ERROR,
                        "DataLab transport request failed", true, exception);
                if (attempt == maxAttempts) {
                    throw lastFailure;
                }
            }
        }
        throw lastFailure == null
                ? failure(DataLabClientFailureKind.UPSTREAM_TIMEOUT_OR_GATEWAY_ERROR,
                "DataLab page timeout budget exhausted", true)
                : lastFailure;
    }

    private boolean sleepBeforeRetry(DataLabClientException exception, Instant deadline) throws InterruptedException {
        long seconds = exception instanceof RetryAfterException retryAfter ? retryAfter.retryAfterSeconds() : 0L;
        if (seconds <= 0) {
            return true;
        }
        if (!Instant.now().plusSeconds(seconds).isBefore(deadline)) {
            return false;
        }
        sleeper.sleep(seconds);
        return true;
    }

    private DataLabVisitorPage parse(DataLabVisitorRequest request, int requestedPage, DataLabHttpResponse response) {
        if (response.statusCode() == 429) {
            throw retryAfterFailure(DataLabClientFailureKind.RATE_LIMITED, "DataLab rate limited request", true, response);
        }
        if (response.statusCode() == 401 || response.statusCode() == 403) {
            throw failure(DataLabClientFailureKind.AUTH_OR_PERMISSION_ERROR, "DataLab authentication failed", false);
        }
        if (response.statusCode() >= 500) {
            throw retryAfterFailure(DataLabClientFailureKind.UPSTREAM_TIMEOUT_OR_GATEWAY_ERROR,
                    "DataLab upstream gateway failed", true, response);
        }
        if (response.statusCode() >= 400) {
            throw failure(DataLabClientFailureKind.PROVIDER_PARAMETER_ERROR, "DataLab rejected request parameters", false);
        }

        final JsonNode body;
        try {
            JsonNode root = objectMapper.readTree(response.body());
            JsonNode envelope = root.has("response") ? root.path("response") : root;
            JsonNode header = envelope.has("header") ? envelope.path("header") : envelope;
            String resultCode = text(header.path("resultCode"));
            if (!SUCCESS_CODE.equals(resultCode)) {
                throw providerFailure(resultCode, text(header.path("resultMsg")), response);
            }
            body = envelope.path("body");
        } catch (DataLabClientException exception) {
            throw exception;
        } catch (Exception exception) {
            throw failure(DataLabClientFailureKind.SCHEMA_DRIFT, "Malformed DataLab JSON envelope", false);
        }

        int pageNo = requiredPositiveInt(body.path("pageNo"), "pageNo");
        int numOfRows = requiredPositiveInt(body.path("numOfRows"), "numOfRows");
        int totalCount = requiredNonNegativeInt(body.path("totalCount"), "totalCount");
        List<DataLabVisitorRecord> records = records(request.scope(), body.path("items").path("item"));
        if (pageNo != requestedPage) {
            throw failure(DataLabClientFailureKind.SCHEMA_DRIFT, "DataLab response pageNo does not match requested page", false);
        }
        return new DataLabVisitorPage(pageNo, numOfRows, totalCount,
                totalCount == 0 || pageNo * numOfRows >= totalCount, records);
    }

    private DataLabClientException providerFailure(String code, String message, DataLabHttpResponse response) {
        String detail = message == null ? "DataLab provider returned error code " + code : message;
        return switch (code == null ? "" : code) {
            case "05" -> retryAfterFailure(DataLabClientFailureKind.UPSTREAM_TIMEOUT_OR_GATEWAY_ERROR, detail, true, response);
            case "20" -> failure(DataLabClientFailureKind.AUTH_OR_PERMISSION_ERROR, detail, false);
            case "22", "23" -> retryAfterFailure(DataLabClientFailureKind.RATE_LIMITED, detail, true, response);
            default -> failure(DataLabClientFailureKind.PROVIDER_PARAMETER_ERROR, detail, false);
        };
    }

    private List<DataLabVisitorRecord> records(DataLabVisitorRequest.Scope scope, JsonNode itemNode) {
        if (itemNode.isMissingNode() || itemNode.isNull()) {
            return List.of();
        }
        var nodes = new ArrayList<JsonNode>();
        if (itemNode.isArray()) {
            itemNode.forEach(nodes::add);
        } else if (itemNode.isObject()) {
            nodes.add(itemNode);
        } else {
            throw failure(DataLabClientFailureKind.SCHEMA_DRIFT, "DataLab items.item has invalid shape", false);
        }
        return nodes.stream().map(node -> record(scope, node)).toList();
    }

    private DataLabVisitorRecord record(DataLabVisitorRequest.Scope scope, JsonNode item) {
        String codeField = scope == DataLabVisitorRequest.Scope.METROPOLITAN ? "areaCode" : "signguCode";
        String nameField = scope == DataLabVisitorRequest.Scope.METROPOLITAN ? "areaNm" : "signguNm";
        try {
            String count = text(item.path("touNum"));
            return new DataLabVisitorRecord(
                    scope,
                    LocalDate.parse(requiredText(item.path("baseYmd"), "baseYmd"), YMD),
                    requiredText(item.path(codeField), codeField),
                    requiredText(item.path(nameField), nameField),
                    requiredText(item.path("touDivCd"), "touDivCd"),
                    text(item.path("touDivNm")),
                    count == null ? null : roundedVisitorCount(count));
        } catch (DataLabClientException exception) {
            throw exception;
        } catch (Exception exception) {
            throw failure(DataLabClientFailureKind.SCHEMA_DRIFT, "DataLab visitor item has invalid fields", false);
        }
    }

    private URI uri(DataLabVisitorRequest request, int pageNo) {
        Map<String, String> parameters = new LinkedHashMap<>();
        parameters.put("MobileOS", "ETC");
        parameters.put("MobileApp", properties.mobileApp());
        parameters.put("serviceKey", properties.serviceKey());
        parameters.put("startYmd", request.startDate().format(YMD));
        parameters.put("endYmd", request.endDate().format(YMD));
        parameters.put("numOfRows", Integer.toString(request.numOfRows()));
        parameters.put("pageNo", Integer.toString(pageNo));
        parameters.put("_type", "json");
        var query = new StringJoiner("&");
        parameters.forEach((key, value) -> query.add(encode(key) + "=" + encode(value)));
        String base = properties.baseUri().toString();
        return URI.create((base.endsWith("/") ? base.substring(0, base.length() - 1) : base)
                + "/" + request.scope().operation() + "?" + query);
    }

    private int requiredPositiveInt(JsonNode node, String field) {
        int value = requiredNonNegativeInt(node, field);
        if (value <= 0) {
            throw failure(DataLabClientFailureKind.SCHEMA_DRIFT, "DataLab " + field + " must be positive", false);
        }
        return value;
    }

    private int requiredNonNegativeInt(JsonNode node, String field) {
        if (!node.canConvertToInt() || node.asInt() < 0) {
            throw failure(DataLabClientFailureKind.SCHEMA_DRIFT, "DataLab " + field + " is invalid", false);
        }
        return node.asInt();
    }

    private String requiredText(JsonNode node, String field) {
        String value = text(node);
        if (value == null) {
            throw failure(DataLabClientFailureKind.SCHEMA_DRIFT, "DataLab " + field + " is missing", false);
        }
        return value;
    }

    /**
     * DataLab returns statistically estimated visitor counts as decimal values. The public
     * VisitReview contract deliberately exposes an integer number of persons, so round to the
     * nearest whole person instead of failing a complete nationwide snapshot on a value such as
     * {@code 153254.5}.
     */
    private long roundedVisitorCount(String count) {
        return new BigDecimal(count).setScale(0, RoundingMode.HALF_UP).longValueExact();
    }

    private String text(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        String value = node.asText().trim();
        return value.isEmpty() ? null : value;
    }

    private Duration shorterOf(Duration first, Duration second) {
        return first.compareTo(second) <= 0 ? first : second;
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private DataLabClientException failure(DataLabClientFailureKind kind, String message, boolean retryable) {
        return new DataLabClientException(kind, message, retryable);
    }

    private DataLabClientException retryAfterFailure(
            DataLabClientFailureKind kind, String message, boolean retryable, DataLabHttpResponse response) {
        return new RetryAfterException(kind, message, retryable, response.retryAfterSeconds());
    }

    private record DataLabVisitorPage(
            int pageNo,
            int numOfRows,
            int totalCount,
            boolean isLastPage,
            List<DataLabVisitorRecord> records
    ) {
    }

    private static final class RetryAfterException extends DataLabClientException {
        private final Long retryAfterSeconds;

        private RetryAfterException(DataLabClientFailureKind kind, String message, boolean retryable, Long retryAfterSeconds) {
            super(kind, message, retryable);
            this.retryAfterSeconds = retryAfterSeconds;
        }

        private long retryAfterSeconds() {
            return retryAfterSeconds == null ? 0L : retryAfterSeconds;
        }
    }
}
