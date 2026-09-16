package com.yrootlab.onmaru.audio.query;

import java.net.URI;
import java.util.Locale;
import java.util.Set;

public final class OdiiPublicAudioUrlPolicy {

    private final Set<String> allowedHosts;

    public OdiiPublicAudioUrlPolicy(Set<String> allowedHosts) {
        this.allowedHosts = allowedHosts.stream()
                .map(host -> host.toLowerCase(Locale.ROOT))
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    public boolean allows(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        try {
            var uri = URI.create(value);
            return "https".equalsIgnoreCase(uri.getScheme())
                    && uri.getHost() != null
                    && allowedHosts.contains(uri.getHost().toLowerCase(Locale.ROOT))
                    && uri.getUserInfo() == null
                    && uri.getQuery() == null
                    && uri.getFragment() == null;
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }
}
