package com.yrootlab.onmaru.tourism.catalog.client;

import java.net.http.HttpHeaders;

public record TourApiResponse(
        String operation,
        int httpStatus,
        HttpHeaders headers,
        String body
) {
}
