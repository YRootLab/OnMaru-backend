package com.yrootlab.onmaru.tourism.catalog.client;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.StringJoiner;

public final class TourApiUriBuilder {

    private static final String REDACTED = "<REDACTED>";

    private final URI baseUri;
    private final String serviceKey;
    private final String mobileApp;

    public TourApiUriBuilder(URI baseUri, String serviceKey, String mobileApp) {
        this.baseUri = Objects.requireNonNull(baseUri, "baseUri must not be null");
        this.serviceKey = requireNonBlank(serviceKey, "serviceKey");
        this.mobileApp = requireNonBlank(mobileApp, "mobileApp");
    }

    public URI areaBasedList(int pageNo, int numOfRows, Map<String, String> optionalParams) {
        Map<String, String> params = pageParams(pageNo, numOfRows);
        params.putAll(Objects.requireNonNull(optionalParams, "optionalParams must not be null"));
        return build("areaBasedList2", params);
    }

    public URI locationBasedList(int pageNo, int numOfRows, Map<String, String> optionalParams) {
        Map<String, String> params = pageParams(pageNo, numOfRows);
        params.putAll(Objects.requireNonNull(optionalParams, "optionalParams must not be null"));
        return build("locationBasedList2", params);
    }

    public URI searchKeyword(String keyword, int pageNo, int numOfRows) {
        Map<String, String> params = pageParams(pageNo, numOfRows);
        params.put("keyword", requireNonBlank(keyword, "keyword"));
        return build("searchKeyword2", params);
    }

    public URI areaCode(int pageNo, int numOfRows) {
        return build("areaCode2", pageParams(pageNo, numOfRows));
    }

    public URI detailCommon(String contentId, String contentTypeId) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("contentId", requireNonBlank(contentId, "contentId"));
        params.put("contentTypeId", requireNonBlank(contentTypeId, "contentTypeId"));
        params.put("defaultYN", "Y");
        params.put("firstImageYN", "Y");
        params.put("areacodeYN", "Y");
        params.put("catcodeYN", "Y");
        params.put("addrinfoYN", "Y");
        params.put("mapinfoYN", "Y");
        params.put("overviewYN", "Y");
        return build("detailCommon2", params);
    }

    public static String redactServiceKey(URI uri) {
        String raw = uri.toString();
        String encodedRedacted = encode(REDACTED);
        return raw.replaceAll("serviceKey=[^&]*", "serviceKey=" + encodedRedacted);
    }

    private URI build(String operation, Map<String, String> operationParams) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("MobileOS", "ETC");
        params.put("MobileApp", mobileApp);
        params.put("_type", "json");
        params.put("serviceKey", serviceKeyQueryValue());
        params.putAll(operationParams);

        StringJoiner query = new StringJoiner("&");
        params.forEach((key, value) -> query.add(encode(key) + "=" + encodeValue(key, value)));
        return URI.create(trimTrailingSlash(baseUri.toString()) + "/" + operation + "?" + query);
    }

    private Map<String, String> pageParams(int pageNo, int numOfRows) {
        if (pageNo < 1) {
            throw new IllegalArgumentException("pageNo must be greater than or equal to 1");
        }
        if (numOfRows < 1) {
            throw new IllegalArgumentException("numOfRows must be greater than or equal to 1");
        }
        Map<String, String> params = new LinkedHashMap<>();
        params.put("pageNo", Integer.toString(pageNo));
        params.put("numOfRows", Integer.toString(numOfRows));
        return params;
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private String encodeValue(String key, String value) {
        if ("serviceKey".equals(key) && looksPercentEncoded(value)) {
            return value;
        }
        return encode(value);
    }

    private String serviceKeyQueryValue() {
        return serviceKey;
    }

    private boolean looksPercentEncoded(String value) {
        return value.matches(".*%[0-9a-fA-F]{2}.*");
    }

    private String trimTrailingSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    private String requireNonBlank(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
