package com.yrootlab.onmaru.web.admission;

import java.util.List;
import java.util.Set;

public record TrustedProxyProperties(Set<String> remoteAddresses) {
    public TrustedProxyProperties(List<String> remoteAddresses) {
        this(Set.copyOf(remoteAddresses));
    }

    public boolean isTrusted(String remoteAddress) {
        return remoteAddress != null && remoteAddresses.contains(remoteAddress);
    }
}
