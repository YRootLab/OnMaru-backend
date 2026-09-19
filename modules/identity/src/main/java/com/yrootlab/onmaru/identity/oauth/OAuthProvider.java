package com.yrootlab.onmaru.identity.oauth;

public record OAuthProvider(String name, String issuer) {

    public OAuthProvider {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("provider name must not be blank");
        }
        if (issuer == null || issuer.isBlank()) {
            throw new IllegalArgumentException("provider issuer must not be blank");
        }
    }

    boolean matches(ExternalIdentity identity) {
        return name.equals(identity.provider()) && issuer.equals(identity.issuer());
    }
}
