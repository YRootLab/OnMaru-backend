package com.yrootlab.onmaru.identity.oauth;

public final class OAuthStateRejectedException extends RuntimeException {

    public OAuthStateRejectedException(String message) {
        super(message);
    }
}
