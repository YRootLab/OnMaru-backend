package com.yrootlab.onmaru.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

@Component
public final class CorsOriginPolicy {

    private final String[] allowedOrigins;
    private final Set<String> allowedOriginSet;

    public CorsOriginPolicy(@Value("${onmaru.web.cors.allowed-origins}") String configuredOrigins) {
        this.allowedOrigins = Arrays.stream(configuredOrigins.split(","))
                .map(String::trim)
                .filter(origin -> !origin.isEmpty())
                .distinct()
                .toArray(String[]::new);
        if (allowedOrigins.length == 0) {
            throw new IllegalArgumentException("onmaru.web.cors.allowed-origins must contain at least one origin");
        }
        this.allowedOriginSet = Collections.unmodifiableSet(new LinkedHashSet<>(Arrays.asList(allowedOrigins)));
    }

    public String[] allowedOrigins() {
        return allowedOrigins.clone();
    }

    public boolean allows(String origin) {
        return origin != null && allowedOriginSet.contains(origin);
    }
}
