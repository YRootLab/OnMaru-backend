package com.yrootlab.onmaru.tourism.audio.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public final class OdiiEnvelopeParser {

    private final ObjectMapper objectMapper;

    public OdiiEnvelopeParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public OdiiPage parse(String operation, byte[] responseBody) {
        try {
            JsonNode root = objectMapper.readTree(responseBody);
            JsonNode response = root.path("response");
            JsonNode header = response.path("header");
            if (!"0000".equals(header.path("resultCode").asText())) {
                throw providerError(root);
            }

            JsonNode body = response.path("body");
            int page = positiveInt(body, "pageNo");
            long totalCount = nonNegativeLong(body, "totalCount");
            List<OdiiSourceItem> items = readItems(operation, body.path("items"));
            int pageSize = pageSize(body, totalCount, items);
            if (items.size() > pageSize) {
                throw schemaDrift("item count exceeds numOfRows");
            }
            boolean lastPage = totalCount == 0 || (long) page * pageSize >= totalCount;
            return new OdiiPage(items, page, pageSize, totalCount, lastPage);
        } catch (OdiiParseException exception) {
            throw exception;
        } catch (IOException | RuntimeException exception) {
            throw new OdiiParseException("ODII_SCHEMA_DRIFT: invalid JSON envelope", exception);
        }
    }

    private List<OdiiSourceItem> readItems(String operation, JsonNode itemsNode) {
        if (itemsNode.isTextual() && itemsNode.asText().isEmpty()) {
            return List.of();
        }
        JsonNode itemNode = itemsNode.path("item");
        if (itemNode.isMissingNode() || itemNode.isNull()) {
            throw schemaDrift("missing items.item");
        }

        List<OdiiSourceItem> items = new ArrayList<>();
        if (itemNode.isArray()) {
            itemNode.forEach(item -> items.add(readItem(operation, item)));
        } else if (itemNode.isObject()) {
            items.add(readItem(operation, itemNode));
        } else {
            throw schemaDrift("items.item must be an object or array");
        }
        return List.copyOf(items);
    }

    private OdiiSourceItem readItem(String operation, JsonNode item) {
        return new OdiiSourceItem(
                requiredText(operation, "operation"),
                requiredText(item, "tid"),
                requiredText(item, "tlid"),
                requiredText(item, "stid"),
                requiredText(item, "stlid"),
                requiredText(item, "title"),
                text(item, "audioTitle"),
                text(item, "script"),
                text(item, "audioUrl"),
                text(item, "imageUrl"),
                text(item, "playTime"),
                text(item, "mapX"),
                text(item, "mapY"),
                requiredText(item, "langCode"),
                text(item, "createdtime"),
                text(item, "modifiedtime")
        );
    }

    private OdiiParseException providerError(JsonNode root) {
        String resultCode = root.path("response").path("header").path("resultCode").asText();
        if (resultCode.isBlank()) {
            resultCode = root.path("OpenAPI_ServiceResponse")
                    .path("cmmMsgHeader")
                    .path("returnReasonCode")
                    .asText("UNKNOWN");
        }
        boolean retryable = resultCode.equals("05") || resultCode.equals("22") || resultCode.equals("23");
        return new OdiiParseException("ODII_PROVIDER_ERROR: " + resultCode, retryable);
    }

    private int positiveInt(JsonNode node, String field) {
        long value = nonNegativeLong(node, field);
        if (value <= 0 || value > Integer.MAX_VALUE) {
            throw schemaDrift(field + " must be a positive integer");
        }
        return (int) value;
    }

    private int pageSize(JsonNode body, long totalCount, List<OdiiSourceItem> items) {
        long value = nonNegativeLong(body, "numOfRows");
        if (value == 0 && totalCount == 0 && items.isEmpty()) {
            return 0;
        }
        if (value <= 0 || value > Integer.MAX_VALUE) {
            throw schemaDrift("numOfRows must be positive unless the result is empty");
        }
        return (int) value;
    }

    private long nonNegativeLong(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (!value.canConvertToLong() || value.longValue() < 0) {
            throw schemaDrift(field + " must be a non-negative integer");
        }
        return value.longValue();
    }

    private String requiredText(JsonNode node, String field) {
        return requiredText(text(node, field), field);
    }

    private String requiredText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw schemaDrift(field + " is required");
        }
        return value.trim();
    }

    private String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (value.isMissingNode() || value.isNull()) {
            return "";
        }
        if (!value.isValueNode()) {
            throw schemaDrift(field + " must be scalar");
        }
        return value.asText();
    }

    private OdiiParseException schemaDrift(String detail) {
        return new OdiiParseException("ODII_SCHEMA_DRIFT: " + detail);
    }
}
