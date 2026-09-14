package com.yrootlab.onmaru.web.admission;

import jakarta.servlet.http.HttpServletRequest;

public final class ClientIdentityResolver {

    private final TrustedProxyProperties trustedProxyProperties;

    public ClientIdentityResolver(TrustedProxyProperties trustedProxyProperties) {
        this.trustedProxyProperties = trustedProxyProperties;
    }

    public String clientIp(HttpServletRequest request) {
        String remoteAddress = request.getRemoteAddr();
        if (!trustedProxyProperties.isTrusted(remoteAddress)) {
            return remoteAddress;
        }
        String forwardedFor = request.getHeader("X-Forwarded-For");
        if (forwardedFor == null || forwardedFor.isBlank()) {
            return remoteAddress;
        }
        String firstHop = forwardedFor.split(",", 2)[0].trim();
        return firstHop.isBlank() ? remoteAddress : firstHop;
    }
}
