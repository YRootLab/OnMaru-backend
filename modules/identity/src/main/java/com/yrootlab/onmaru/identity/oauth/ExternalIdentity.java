package com.yrootlab.onmaru.identity.oauth;

public record ExternalIdentity(String provider, String issuer, String subject) {

    public ExternalIdentity {
        if (provider == null || provider.isBlank()) {
            throw new IllegalArgumentException("provider must not be blank");
        }
        if (issuer == null || issuer.isBlank()) {
            throw new IllegalArgumentException("issuer must not be blank");
        }
        if (subject == null || subject.isBlank()) {
            throw new IllegalArgumentException("subject must not be blank");
        }
    }
}
