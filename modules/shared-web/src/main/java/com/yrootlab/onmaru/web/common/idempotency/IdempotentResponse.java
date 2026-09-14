package com.yrootlab.onmaru.web.common.idempotency;

import java.util.LinkedHashMap;
import java.util.Map;

public record IdempotentResponse(int status, Map<String, String> headers, Object body) {

    public IdempotentResponse {
        headers = headers == null ? Map.of() : Map.copyOf(headers);
    }

    public static IdempotentResponse accepted(Object body) {
        return new IdempotentResponse(202, Map.of(), body);
    }

    public static IdempotentResponse created(String location, Object body) {
        var headers = new LinkedHashMap<String, String>();
        headers.put("Location", location);
        return new IdempotentResponse(201, headers, body);
    }
}
