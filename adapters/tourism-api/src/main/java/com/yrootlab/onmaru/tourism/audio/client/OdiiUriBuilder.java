package com.yrootlab.onmaru.tourism.audio.client;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.StringJoiner;

public final class OdiiUriBuilder {

    private static final String REDACTED = "<REDACTED>";

    private final URI baseUri;
    private final String serviceKey;
    private final String mobileApp;

    public OdiiUriBuilder(URI baseUri, String serviceKey, String mobileApp) {
        this.baseUri = Objects.requireNonNull(baseUri, "baseUri must not be null");
        this.serviceKey = requireNonBlank(serviceKey, "serviceKey");
        this.mobileApp = requireNonBlank(mobileApp, "mobileApp");
    }

    public URI storyBased(String langCode, String tid, String tlid, int pageNo, int numOfRows) {
        Map<String, String> params = pageParams(langCode, pageNo, numOfRows);
        params.put("tid", requireNonBlank(tid, "tid"));
        params.put("tlid", requireNonBlank(tlid, "tlid"));
        return build("storyBasedList", params);
    }

    public URI storySearch(String langCode, String keyword, int pageNo, int numOfRows) {
        Map<String, String> params = pageParams(langCode, pageNo, numOfRows);
        params.put("keyword", requireNonBlank(keyword, "keyword"));
        return build("storySearchList", params);
    }

    public URI storyLocationBased(
            String langCode,
            String longitude,
            String latitude,
            int radiusMeters,
            int pageNo,
            int numOfRows
    ) {
        if (radiusMeters < 1) {
            throw new IllegalArgumentException("radiusMeters must be positive");
        }
        Map<String, String> params = pageParams(langCode, pageNo, numOfRows);
        params.put("mapX", requireNonBlank(longitude, "longitude"));
        params.put("mapY", requireNonBlank(latitude, "latitude"));
        params.put("radius", Integer.toString(radiusMeters));
        return build("storyLocationBasedList", params);
    }

    public static String redactServiceKey(URI uri) {
        return uri.toString().replaceAll("serviceKey=[^&]*", "serviceKey=" + encode(REDACTED));
    }

    private Map<String, String> pageParams(String langCode, int pageNo, int numOfRows) {
        if (pageNo < 1 || numOfRows < 1) {
            throw new IllegalArgumentException("pageNo and numOfRows must be positive");
        }
        Map<String, String> params = new LinkedHashMap<>();
        params.put("langCode", requireNonBlank(langCode, "langCode"));
        params.put("pageNo", Integer.toString(pageNo));
        params.put("numOfRows", Integer.toString(numOfRows));
        return params;
    }

    private URI build(String operation, Map<String, String> operationParams) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("MobileOS", "ETC");
        params.put("MobileApp", mobileApp);
        params.put("_type", "json");
        params.put("serviceKey", serviceKey);
        params.putAll(operationParams);
        StringJoiner query = new StringJoiner("&");
        params.forEach((key, value) -> query.add(encode(key) + "=" + encodeValue(key, value)));
        String base = baseUri.toString();
        return URI.create((base.endsWith("/") ? base.substring(0, base.length() - 1) : base)
                + "/" + operation + "?" + query);
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String encodeValue(String key, String value) {
        if ("serviceKey".equals(key) && value.matches(".*%[0-9a-fA-F]{2}.*")) {
            return value;
        }
        return encode(value);
    }

    private static String requireNonBlank(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
