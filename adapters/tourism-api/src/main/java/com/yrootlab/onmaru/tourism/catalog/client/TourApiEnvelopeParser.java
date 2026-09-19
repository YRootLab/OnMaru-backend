package com.yrootlab.onmaru.tourism.catalog.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.xml.XmlMapper;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class TourApiEnvelopeParser {

    private static final String SUCCESS_CODE = "0000";

    private final ObjectMapper objectMapper;
    private final XmlMapper xmlMapper;

    public TourApiEnvelopeParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.xmlMapper = new XmlMapper();
    }

    public TourApiParseResult parse(TourApiResponse response) {
        if (!hasSupportedContentType(response)) {
            TourApiParseResult statusError = classifyByHttpStatus(response, null);
            if (statusError != null) {
                return statusError;
            }
            return error(response, TourApiOutcomeKind.SCHEMA_DRIFT, null, "Unexpected content type", false);
        }

        JsonNode root;
        try {
            root = readEnvelope(response);
        } catch (IOException exception) {
            return error(response, TourApiOutcomeKind.SCHEMA_DRIFT, null, "Malformed provider envelope", false);
        }

        TourApiParseResult statusError = classifyByHttpStatus(response, root);
        if (statusError != null) {
            return statusError;
        }

        String serviceResponseCode = serviceResponseCode(root);
        if (serviceResponseCode != null) {
            return classifyProviderEnvelope(response, serviceResponseCode, serviceResponseMessage(root));
        }

        JsonNode responseNode = root.path("response");
        JsonNode header = responseNode.path("header");
        String resultCode = textOrNull(header.path("resultCode"));
        if (!SUCCESS_CODE.equals(resultCode)) {
            return classifyProviderEnvelope(response, resultCode, textOrNull(header.path("resultMsg")));
        }

        JsonNode body = responseNode.path("body");
        if (body.isMissingNode()) {
            return error(response, TourApiOutcomeKind.SCHEMA_DRIFT, resultCode, "Missing response.body", false);
        }

        Integer pageNo = positiveInt(body.path("pageNo"));
        Integer numOfRows = positiveInt(body.path("numOfRows"));
        Integer totalCount = nonNegativeInt(body.path("totalCount"));
        if (pageNo == null || numOfRows == null || totalCount == null) {
            return error(response, TourApiOutcomeKind.SCHEMA_DRIFT, resultCode, "Invalid pagination fields", false);
        }
        List<TourApiSourceRecord> items = parseItems(response.operation(), body.path("items").path("item"));
        boolean isLastPage = totalCount == 0 || pageNo * numOfRows >= totalCount;

        return TourApiParseResult.success(new TourApiPage(
                response.operation(),
                pageNo,
                numOfRows,
                totalCount,
                isLastPage,
                List.copyOf(items)
        ));
    }

    private TourApiParseResult classifyByHttpStatus(TourApiResponse response, JsonNode root) {
        String providerCode = root == null ? null : serviceResponseCode(root);
        String providerMessage = root == null ? null : serviceResponseMessage(root);
        if (response.httpStatus() == 429) {
            return error(response, TourApiOutcomeKind.RATE_LIMITED, providerCode, providerMessage, true);
        }
        if (response.httpStatus() == 401 || response.httpStatus() == 403) {
            return error(response, TourApiOutcomeKind.AUTH_OR_PERMISSION_ERROR, providerCode, providerMessage, false);
        }
        if (response.httpStatus() >= 500) {
            return error(response, TourApiOutcomeKind.UPSTREAM_TIMEOUT_OR_GATEWAY_ERROR, providerCode, providerMessage, true);
        }
        if (response.httpStatus() >= 400) {
            return error(response, TourApiOutcomeKind.PROVIDER_PARAMETER_ERROR, providerCode, providerMessage, false);
        }
        return null;
    }

    private TourApiParseResult classifyProviderEnvelope(
            TourApiResponse response,
            String providerCode,
            String providerMessage
    ) {
        return switch (providerCode == null ? "" : providerCode) {
            case "05" -> error(response, TourApiOutcomeKind.UPSTREAM_TIMEOUT_OR_GATEWAY_ERROR, providerCode, providerMessage, true);
            case "20" -> error(response, TourApiOutcomeKind.AUTH_OR_PERMISSION_ERROR, providerCode, providerMessage, false);
            case "22", "23" -> error(response, TourApiOutcomeKind.RATE_LIMITED, providerCode, providerMessage, true);
            default -> error(response, TourApiOutcomeKind.PROVIDER_PARAMETER_ERROR, providerCode, providerMessage, false);
        };
    }

    private List<TourApiSourceRecord> parseItems(String operation, JsonNode itemNode) {
        if (itemNode.isMissingNode() || itemNode.isNull() || itemNode.isTextual()) {
            return List.of();
        }

        List<TourApiSourceRecord> records = new ArrayList<>();
        if (itemNode.isArray()) {
            itemNode.forEach(item -> records.add(new TourApiSourceRecord(operation, item)));
            return records;
        }

        if (itemNode.isObject()) {
            return List.of(new TourApiSourceRecord(operation, itemNode));
        }

        return List.of();
    }

    private JsonNode readEnvelope(TourApiResponse response) throws IOException {
        if (hasXmlContentType(response)) {
            return xmlMapper.readTree(response.body());
        }
        return objectMapper.readTree(response.body());
    }

    private boolean hasSupportedContentType(TourApiResponse response) {
        return hasJsonContentType(response) || hasXmlContentType(response);
    }

    private boolean hasJsonContentType(TourApiResponse response) {
        return response.headers().firstValue("Content-Type")
                .map(value -> value.toLowerCase(Locale.ROOT))
                .filter(value -> value.contains("application/json") || value.contains("+json"))
                .isPresent();
    }

    private boolean hasXmlContentType(TourApiResponse response) {
        return response.headers().firstValue("Content-Type")
                .map(value -> value.toLowerCase(Locale.ROOT))
                .filter(value -> value.contains("application/xml") || value.contains("text/xml") || value.contains("+xml"))
                .isPresent();
    }

    private TourApiParseResult error(
            TourApiResponse response,
            TourApiOutcomeKind kind,
            String providerCode,
            String providerMessage,
            boolean retryable
    ) {
        Long retryAfterSeconds = response.headers().firstValue("Retry-After")
                .map(this::parseRetryAfterSeconds)
                .orElse(null);
        return TourApiParseResult.error(new TourApiError(
                response.operation(),
                kind,
                providerCode,
                providerMessage,
                retryable,
                retryAfterSeconds
        ));
    }

    private Long parseRetryAfterSeconds(String value) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private Integer positiveInt(JsonNode node) {
        Integer value = nonNegativeInt(node);
        if (value == null || value <= 0) {
            return null;
        }
        return value;
    }

    private Integer nonNegativeInt(JsonNode node) {
        if (!node.canConvertToInt()) {
            return null;
        }
        int value = node.asInt();
        return value < 0 ? null : value;
    }

    private String serviceResponseCode(JsonNode root) {
        return textOrNull(serviceResponseHeader(root).path("returnReasonCode"));
    }

    private String serviceResponseMessage(JsonNode root) {
        JsonNode header = serviceResponseHeader(root);
        String authMessage = textOrNull(header.path("returnAuthMsg"));
        return authMessage == null ? textOrNull(header.path("errMsg")) : authMessage;
    }

    private JsonNode serviceResponseHeader(JsonNode root) {
        JsonNode nested = root.path("OpenAPI_ServiceResponse").path("cmmMsgHeader");
        JsonNode direct = nested.isMissingNode() ? root.path("cmmMsgHeader") : nested;
        return direct.isMissingNode() ? root.path("cmmMsgHeader") : direct;
    }

    private String textOrNull(JsonNode node) {
        if (node.isMissingNode() || node.isNull()) {
            return null;
        }
        return node.asText();
    }
}
