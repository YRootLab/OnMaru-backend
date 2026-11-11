package com.yrootlab.onmaru.mapcollab.domain.exception;

import java.util.Collections;
import java.util.Map;

public class MapCollabException extends RuntimeException {

    private final String code;
    private final int statusCode;
    private final Map<String, Object> details;

    public MapCollabException(String code, int statusCode, String message) {
        this(code, statusCode, message, Collections.emptyMap());
    }

    public MapCollabException(String code, int statusCode, String message, Map<String, Object> details) {
        super(message);
        this.code = code;
        this.statusCode = statusCode;
        this.details = details != null ? Collections.unmodifiableMap(details) : Collections.emptyMap();
    }

    public String getCode() {
        return code;
    }

    public int getStatusCode() {
        return statusCode;
    }

    public Map<String, Object> getDetails() {
        return details;
    }
}
